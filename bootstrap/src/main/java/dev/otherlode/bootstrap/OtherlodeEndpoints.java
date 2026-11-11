package dev.otherlode.bootstrap;

import java.lang.System.Logger.Level;
import java.lang.invoke.MethodHandleInfo;
import java.lang.ref.WeakReference;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The one seam inlined framework advice calls to record and count HTTP endpoints.
 *
 * <p>Lives in the bootstrap classloader, so advice woven into any classloader's classes can reach
 * it. The agent plugs its endpoint registry in through {@link #install(Resolver)} at premain.
 *
 * <p>Every method is safe to call from any thread, before or after {@link #install}, and never
 * throws. Before {@link #install}, {@link #register}, {@link #recordDispatch}, {@link
 * #recordDispatchIfUnowned}, {@link #declare}, {@link #moduleFailed} and {@link #moduleDisabled}
 * are buffered and replayed in order once a resolver is installed. A call other than {@link
 * #moduleFailed} or {@link #moduleDisabled} that finds the buffer full is dropped and disables its
 * module.
 *
 * <p>A resolver failure is logged once per method and module. A method that returns an entry
 * returns null when the call is buffered or dropped, its module is disabled or the resolver throws.
 */
public final class OtherlodeEndpoints {
    /** The template is a path: normalised, with any context path folded in. */
    public static final int TEMPLATE_PATH = 0;

    /** The template is the regular expression a framework matches a route by, kept exactly as written. */
    public static final int TEMPLATE_REGEX = 1;

    /**
     * The template is a servlet mapping no path can express, such as {@code *.do} or the default
     * mapping {@code /}, kept exactly as written.
     */
    public static final int TEMPLATE_SERVLET_PATTERN = 2;

    /** Implemented by the agent; backs the entry points that look up, record, count and disable endpoints. */
    public interface Resolver {
        Object lookup(Object key);

        Object register(
                Object key,
                String framework,
                String verb,
                String verbatimTemplate,
                String contextPath,
                String handlerClass,
                String handlerMethod,
                String handlerDescriptor,
                int templateKind);

        Object recordDispatch(
                Object key,
                String framework,
                String verb,
                String verbatimTemplate,
                String contextPath,
                String handlerClass,
                int templateKind);

        Object recordDispatchIfUnowned(
                Object key,
                String framework,
                String verb,
                String verbatimTemplate,
                String contextPath,
                String handlerClass,
                int templateKind);

        void declare(String module, Object frameworkObject);

        void hit(Object entry);

        void attachHandler(Object entry, String handlerClass, String handlerMethod, String handlerDescriptor);

        void disableModule(String module, int kind, String reason);
    }

    /**
     * The method a lambda class calls: its owner's {@link Class#getName()}, its name, and its JVM
     * descriptor. The descriptor is the method's own, not the interface's, so it can start with values
     * the lambda captured.
     */
    public static final class LambdaImplementation {
        public final String className;
        public final String methodName;
        public final String descriptor;

        LambdaImplementation(String className, String methodName, String descriptor) {
            this.className = className;
            this.methodName = methodName;
            this.descriptor = descriptor;
        }
    }

    private enum RecordKind {
        REGISTER,
        DISPATCH,
        DISPATCH_IF_UNOWNED,
        DECLARE,
        FAILURE,
    }

    private static final class BufferedRecord {
        final RecordKind kind;
        final String module;
        final Object key;
        final String verb;
        final String verbatimTemplate;
        final String contextPath;
        final String handlerClass;
        final String handlerMethod;
        final String handlerDescriptor;
        final String reason;
        final int disableKind;
        final Object frameworkObject;
        final int templateKind;

        private BufferedRecord(
                RecordKind kind,
                String module,
                Object key,
                String verb,
                String verbatimTemplate,
                String contextPath,
                String handlerClass,
                String handlerMethod,
                String handlerDescriptor,
                String reason,
                int disableKind,
                Object frameworkObject,
                int templateKind) {
            this.kind = kind;
            this.module = module;
            this.key = key;
            this.verb = verb;
            this.verbatimTemplate = verbatimTemplate;
            this.contextPath = contextPath;
            this.handlerClass = handlerClass;
            this.handlerMethod = handlerMethod;
            this.handlerDescriptor = handlerDescriptor;
            this.reason = reason;
            this.disableKind = disableKind;
            this.frameworkObject = frameworkObject;
            this.templateKind = templateKind;
        }

        static BufferedRecord forRegister(
                String module,
                Object key,
                String verb,
                String verbatimTemplate,
                String contextPath,
                String handlerClass,
                String handlerMethod,
                String handlerDescriptor,
                int templateKind) {
            return new BufferedRecord(
                    RecordKind.REGISTER,
                    module,
                    key,
                    verb,
                    verbatimTemplate,
                    contextPath,
                    handlerClass,
                    handlerMethod,
                    handlerDescriptor,
                    null,
                    0,
                    null,
                    templateKind);
        }

        static BufferedRecord forDispatch(
                String module,
                Object key,
                String verb,
                String verbatimTemplate,
                String contextPath,
                String handlerClass,
                int templateKind) {
            return new BufferedRecord(
                    RecordKind.DISPATCH,
                    module,
                    key,
                    verb,
                    verbatimTemplate,
                    contextPath,
                    handlerClass,
                    null,
                    null,
                    null,
                    0,
                    null,
                    templateKind);
        }

        static BufferedRecord forDispatchIfUnowned(
                String module,
                Object key,
                String verb,
                String verbatimTemplate,
                String contextPath,
                String handlerClass,
                int templateKind) {
            return new BufferedRecord(
                    RecordKind.DISPATCH_IF_UNOWNED,
                    module,
                    key,
                    verb,
                    verbatimTemplate,
                    contextPath,
                    handlerClass,
                    null,
                    null,
                    null,
                    0,
                    null,
                    templateKind);
        }

        static BufferedRecord forDeclare(String module, Object frameworkObject) {
            return new BufferedRecord(
                    RecordKind.DECLARE, module, null, null, null, null, null, null, null, null, 0, frameworkObject, TEMPLATE_PATH);
        }

        static BufferedRecord forFailure(String module, int kind, String reason) {
            return new BufferedRecord(
                    RecordKind.FAILURE, module, null, null, null, null, null, null, null, reason, kind, null, TEMPLATE_PATH);
        }
    }

    private static final System.Logger LOG = OtherlodeLoggers.get(OtherlodeEndpoints.class.getName());
    private static final int BUFFER_CAPACITY = 4096;
    private static final String REPLAY_BUFFER_OVERFLOW =
            "endpoint replay buffer overflow: records were dropped before a resolver was installed";

    private static final Object BUFFER_LOCK = new Object();
    private static final Deque<BufferedRecord> BUFFER = new ArrayDeque<>();
    private static boolean bufferOverflowLogged;

    private static volatile Resolver resolver;
    // Set only after install has replayed the buffer. The slow path reads resolver instead, so a
    // declare replayed on the installing thread reaches register without buffering again.
    private static volatile Resolver ready;

    private static final Set<String> DISABLED_MODULES = ConcurrentHashMap.newKeySet();
    private static volatile boolean anyDisabled;
    private static final Set<String> FAILURE_LOGGED = ConcurrentHashMap.newKeySet();
    private static final Set<String> DELEGATE_FAILURE_LOGGED = ConcurrentHashMap.newKeySet();

    private static volatile Set<String> handlerInterfaces = Collections.emptySet();
    private static final Map<Class<?>, LambdaImplementation> LAMBDA_IMPLEMENTATIONS = new WeakHashMap<>();
    private static volatile boolean lambdaFailureLogged;

    private OtherlodeEndpoints() {
    }

    /**
     * Installs the resolver, then replays the calls buffered before it in arrival order. A second call replaces
     * the resolver and has nothing to replay.
     */
    public static void install(Resolver newResolver) {
        synchronized (BUFFER_LOCK) {
            resolver = newResolver;
            for (BufferedRecord record : BUFFER) {
                replay(newResolver, record);
            }
            BUFFER.clear();
            ready = newResolver;
        }
    }

    /** Resolves a dispatch key to its endpoint entry, or null with no resolver, a disabled module, or an unknown key. */
    public static Object lookup(String module, Object key) {
        if (isDisabledFast(module)) return null;
        Resolver current = resolver;
        if (current == null) return null;
        try {
            return current.lookup(key);
        } catch (Throwable t) {
            logDelegateFailure(module, "lookup", t);
            return null;
        }
    }

    /** Records an endpoint from a framework's own registration hook. Buffers before a resolver is installed. */
    public static Object register(
            String module,
            Object key,
            String verb,
            String verbatimTemplate,
            String contextPath,
            String handlerClass,
            String handlerMethod,
            String handlerDescriptor) {
        return register(module, key, verb, verbatimTemplate, contextPath, handlerClass, handlerMethod, handlerDescriptor, TEMPLATE_PATH);
    }

    /**
     * Like {@link #register(String, Object, String, String, String, String, String, String)}, with
     * {@code templateKind} one of {@link #TEMPLATE_PATH}, {@link #TEMPLATE_REGEX} and {@link
     * #TEMPLATE_SERVLET_PATTERN}, saying what {@code verbatimTemplate} is. A regex or servlet
     * pattern template is kept as written and {@code contextPath} is not folded into it.
     */
    public static Object register(
            String module,
            Object key,
            String verb,
            String verbatimTemplate,
            String contextPath,
            String handlerClass,
            String handlerMethod,
            String handlerDescriptor,
            int templateKind) {
        if (isDisabledFast(module)) return null;
        Resolver current = ready;
        if (current == null) {
            synchronized (BUFFER_LOCK) {
                current = resolver;
                if (current == null) {
                    buffer(
                            BufferedRecord.forRegister(
                                    module,
                                    key,
                                    verb,
                                    verbatimTemplate,
                                    contextPath,
                                    handlerClass,
                                    handlerMethod,
                                    handlerDescriptor,
                                    templateKind));
                    return null;
                }
            }
        }
        try {
            return current.register(
                    key, module, verb, verbatimTemplate, contextPath, handlerClass, handlerMethod, handlerDescriptor, templateKind);
        } catch (Throwable t) {
            logDelegateFailure(module, "register", t);
            return null;
        }
    }

    /** Records an endpoint the moment a request dispatches to it. Buffers before a resolver is installed. */
    public static Object recordDispatch(
            String module, Object key, String verb, String verbatimTemplate, String contextPath, String handlerClass) {
        return recordDispatch(module, key, verb, verbatimTemplate, contextPath, handlerClass, TEMPLATE_PATH);
    }

    /** Like {@link #recordDispatch(String, Object, String, String, String, String)}, for a template of the given kind. */
    public static Object recordDispatch(
            String module,
            Object key,
            String verb,
            String verbatimTemplate,
            String contextPath,
            String handlerClass,
            int templateKind) {
        if (isDisabledFast(module)) return null;
        Resolver current = ready;
        if (current == null) {
            synchronized (BUFFER_LOCK) {
                current = resolver;
                if (current == null) {
                    buffer(BufferedRecord.forDispatch(module, key, verb, verbatimTemplate, contextPath, handlerClass, templateKind));
                    return null;
                }
            }
        }
        try {
            return current.recordDispatch(key, module, verb, verbatimTemplate, contextPath, handlerClass, templateKind);
        } catch (Throwable t) {
            logDelegateFailure(module, "recordDispatch", t);
            return null;
        }
    }

    /**
     * Like {@link #recordDispatch}, for a route bridge module reading an identity another
     * instrumentation layer already resolved. Returns null and binds nothing when the identity
     * already belongs to an endpoint a framework module owns.
     */
    public static Object recordDispatchIfUnowned(
            String module, Object key, String verb, String verbatimTemplate, String contextPath, String handlerClass) {
        return recordDispatchIfUnowned(module, key, verb, verbatimTemplate, contextPath, handlerClass, TEMPLATE_PATH);
    }

    /** Like {@link #recordDispatchIfUnowned(String, Object, String, String, String, String)}, for a template of the given kind. */
    public static Object recordDispatchIfUnowned(
            String module,
            Object key,
            String verb,
            String verbatimTemplate,
            String contextPath,
            String handlerClass,
            int templateKind) {
        if (isDisabledFast(module)) return null;
        Resolver current = ready;
        if (current == null) {
            synchronized (BUFFER_LOCK) {
                current = resolver;
                if (current == null) {
                    buffer(
                            BufferedRecord.forDispatchIfUnowned(
                                    module, key, verb, verbatimTemplate, contextPath, handlerClass, templateKind));
                    return null;
                }
            }
        }
        try {
            return current.recordDispatchIfUnowned(key, module, verb, verbatimTemplate, contextPath, handlerClass, templateKind);
        } catch (Throwable t) {
            logDelegateFailure(module, "recordDispatchIfUnowned", t);
            return null;
        }
    }

    /**
     * Hands a framework object to a module to walk, for a framework whose routes are not readable
     * from a registration hook's arguments, such as Spring's {@code RouterFunction}. This records
     * no endpoint itself; the module calls {@link #register} for what it finds. Buffers before a
     * resolver is installed.
     */
    public static void declare(String module, Object frameworkObject) {
        if (isDisabledFast(module)) return;
        Resolver current = ready;
        if (current == null) {
            synchronized (BUFFER_LOCK) {
                current = resolver;
                if (current == null) {
                    buffer(BufferedRecord.forDeclare(module, frameworkObject));
                    return;
                }
            }
        }
        try {
            current.declare(module, frameworkObject);
        } catch (Throwable t) {
            logDelegateFailure(module, "declare", t);
        }
    }

    /**
     * Maps each route an OpenTelemetry route prefixer returned to the route it was given, so the
     * route a route bridge reads at span end resolves back to the one a framework module declared.
     *
     * <p>Once the table holds {@code capacity} entries it adds no new route and keeps what it has,
     * though concurrent calls can each add one past it. A prefixed route given by two different routes is
     * ambiguous and resolves to itself. Calls never throw.
     */
    public static final class RoutePrefixTable {
        private static final int MAX_STEPS = 8;
        private static final String AMBIGUOUS = new String("ambiguous");

        private final int capacity;
        private final ConcurrentHashMap<String, String> unprefixedByPrefixed = new ConcurrentHashMap<>();

        public RoutePrefixTable(int capacity) {
            this.capacity = capacity;
        }

        /**
         * Records that a prefixer turned {@code unprefixed} into {@code prefixed}. A null route or an
         * empty {@code unprefixed} records nothing. A route the prefixer left unchanged is recorded as a pair with
         * itself, so a different pair for the same route makes it ambiguous in either order.
         */
        public void record(String prefixed, String unprefixed) {
            if (prefixed == null || unprefixed == null || unprefixed.isEmpty()) return;
            String known = unprefixedByPrefixed.get(prefixed);
            if (known == null) {
                if (unprefixedByPrefixed.size() >= capacity) return;
                known = unprefixedByPrefixed.putIfAbsent(prefixed, unprefixed);
                if (known == null) return;
            }
            if (known != AMBIGUOUS && !known.equals(unprefixed)) {
                unprefixedByPrefixed.put(prefixed, AMBIGUOUS);
            }
        }

        /**
         * Follows {@code route} back through every prefixer that built it. A route the table does
         * not hold, an ambiguous step or a chain that does not end returns {@code route} as given.
         */
        public String resolve(String route) {
            if (route == null) return null;
            String current = route;
            for (int step = 0; step < MAX_STEPS; step++) {
                String next = unprefixedByPrefixed.get(current);
                if (next == null || next.equals(current)) return current;
                if (next == AMBIGUOUS) return route;
                current = next;
            }
            return route;
        }
    }

    /**
     * The most endpoints a module may create at dispatch, and the most request keys it may bind to
     * endpoints it holds. An endpoint the module declared at registration does not count.
     */
    public static final int DISPATCH_LIMIT = 8192;

    private static final RoutePrefixTable ROUTE_PREFIXES = new RoutePrefixTable(8192);

    /**
     * The verb a functional route is counted under for a request method named {@code method}: the
     * method itself when it is one of GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS and TRACE, else
     * {@code *}. A null method is {@code *} too.
     */
    public static String standardVerb(String method) {
        if (method == null) return "*";
        switch (method) {
            case "GET":
            case "HEAD":
            case "POST":
            case "PUT":
            case "PATCH":
            case "DELETE":
            case "OPTIONS":
            case "TRACE":
                return method;
            default:
                return "*";
        }
    }

    /** Records that one of OpenTelemetry's route prefixers turned {@code unprefixed} into {@code prefixed}. Never throws. */
    public static void recordRoutePrefix(String prefixed, String unprefixed) {
        try {
            ROUTE_PREFIXES.record(prefixed, unprefixed);
        } catch (Throwable t) {
            logDelegateFailure(null, "recordRoutePrefix", t);
        }
    }

    /** The route {@code route} was built from through the prefixers recorded so far, or {@code route} itself. Never throws. */
    public static String resolveRoutePrefix(String route) {
        try {
            return ROUTE_PREFIXES.resolve(route);
        } catch (Throwable t) {
            logDelegateFailure(null, "resolveRoutePrefix", t);
            return route;
        }
    }

    /** Increments an endpoint's hit count. A null entry is a no-op. */
    public static void hit(Object entry) {
        if (entry == null) return;
        Resolver current = resolver;
        if (current == null) return;
        try {
            current.hit(entry);
        } catch (Throwable t) {
            logDelegateFailure(null, "hit", t);
        }
    }

    private static final ThreadLocal<WeakReference<Object>> RESOURCE_SERVED = new ThreadLocal<>();

    /**
     * Marks {@code request} as one a functional resources route matched on this thread, so the
     * route mapping's dispatch hook does not count it again.
     */
    public static void resourceServed(Object request) {
        RESOURCE_SERVED.set(new WeakReference<>(request));
    }

    /**
     * Whether {@link #resourceServed} marked {@code request} on this thread. Any mark on this thread
     * is cleared either way.
     */
    public static boolean takeResourceServed(Object request) {
        WeakReference<Object> mark = RESOURCE_SERVED.get();
        if (mark == null) return false;
        RESOURCE_SERVED.remove();
        return mark.get() == request;
    }

    /**
     * Returns the consumer a reactive {@code resources(pattern, location)} route runs on each
     * resource its lookup emits. It takes no Reactor type: advice passes it to {@code
     * Mono.doOnNext}.
     *
     * <p>The consumer marks {@code exchange} through {@link #resourceServed}, then counts the
     * endpoint unless {@code key} is null, which stands for a request that is no call, such as a
     * preflight. {@code entry} is the endpoint the advice resolved for {@code key}, or null when
     * it is not declared; the consumer then looks {@code key} up again and records a discovered
     * endpoint under {@code verb} and {@code template} only when a resource was served. The
     * consumer never throws.
     */
    public static Consumer<Object> resourceEmitted(
            String module, Object exchange, Object entry, Object key, String verb, String template) {
        return new ResourceEmitted(module, exchange, entry, key, verb, template);
    }

    private static final class ResourceEmitted implements Consumer<Object> {
        private final String module;
        private final Object exchange;
        private final Object entry;
        private final Object key;
        private final String verb;
        private final String template;

        ResourceEmitted(String module, Object exchange, Object entry, Object key, String verb, String template) {
            this.module = module;
            this.exchange = exchange;
            this.entry = entry;
            this.key = key;
            this.verb = verb;
            this.template = template;
        }

        @Override
        public void accept(Object resource) {
            try {
                resourceServed(exchange);
                if (key == null) return;
                Object target = entry;
                if (target == null) target = lookup(module, key);
                if (target == null) target = recordDispatch(module, key, verb, template, null, null);
                hit(target);
            } catch (Throwable t) {
                logDelegateFailure(module, "resourceEmitted", t);
            }
        }
    }

    /**
     * What a framework call that registers several endpoints in one go tells the registrations it
     * makes: the object that owns their handlers, and the two paths they are made under.
     */
    public static final class RegistrationScope {
        /** The object whose methods the registered handlers call. */
        public final Object owner;

        /** The path of the endpoints that address one item of a resource. */
        public final String itemPath;

        /** The path of the endpoints that address the resource as a whole. */
        public final String collectionPath;

        RegistrationScope(Object owner, String itemPath, String collectionPath) {
            this.owner = owner;
            this.itemPath = itemPath;
            this.collectionPath = collectionPath;
        }
    }

    private static final ThreadLocal<Deque<RegistrationScope>> REGISTRATION_SCOPES = new ThreadLocal<>();

    /**
     * Opens a scope on this thread, for the registrations a framework call makes before it returns.
     * Every call is paired with {@link #exitRegistrationScope}, which the caller runs even when the
     * framework call throws. This never throws.
     */
    public static void enterRegistrationScope(Object owner, String itemPath, String collectionPath) {
        try {
            Deque<RegistrationScope> scopes = REGISTRATION_SCOPES.get();
            if (scopes == null) {
                scopes = new ArrayDeque<>();
                REGISTRATION_SCOPES.set(scopes);
            }
            scopes.push(new RegistrationScope(owner, itemPath, collectionPath));
        } catch (Throwable t) {
            logDelegateFailure(null, "enterRegistrationScope", t);
        }
    }

    /** Closes the scope {@link #enterRegistrationScope} opened last on this thread. A no-op with none open. */
    public static void exitRegistrationScope() {
        try {
            Deque<RegistrationScope> scopes = REGISTRATION_SCOPES.get();
            if (scopes == null) return;
            scopes.poll();
            if (scopes.isEmpty()) REGISTRATION_SCOPES.remove();
        } catch (Throwable t) {
            logDelegateFailure(null, "exitRegistrationScope", t);
        }
    }

    /** The innermost scope open on this thread, or null when none is. */
    public static RegistrationScope registrationScope() {
        Deque<RegistrationScope> scopes = REGISTRATION_SCOPES.get();
        return scopes == null ? null : scopes.peek();
    }

    /** Attaches a handler class/method/descriptor to an already-resolved entry. A no-op for a null entry or a disabled module. */
    public static void attachHandler(String module, Object entry, String handlerClass, String handlerMethod, String handlerDescriptor) {
        if (entry == null || isDisabledFast(module)) return;
        Resolver current = resolver;
        if (current == null) return;
        try {
            current.attachHandler(entry, handlerClass, handlerMethod, handlerDescriptor);
        } catch (Throwable t) {
            logDelegateFailure(module, "attachHandler", t);
        }
    }

    /** No kind: what a buffered record that disables nothing carries, and a disable that fits none of the kinds below. */
    public static final int KIND_UNSPECIFIED = 0;

    /** A {@link LinkageError}, wherever it was caught: the framework release differs from the one the module was built for. */
    public static final int KIND_LINKAGE_ERROR = 1;

    /** Any other throw from the module's advice. */
    public static final int KIND_ADVICE_FAILED = 2;

    /**
     * The module's matcher or its own {@code transform} threw, a framework class it hooks failed to
     * weave, or the loader of that class cannot see this package.
     */
    public static final int KIND_TRANSFORM_FAILED = 3;

    /** Walking a framework's route objects threw. */
    public static final int KIND_ROUTE_WALK_FAILED = 4;

    /** A hook matched no method on the framework class it hooks. */
    public static final int KIND_HOOK_UNMATCHED = 5;

    /** A module built a router on a runtime where it has no dispatch point to count its routes at, so it declares none. */
    public static final int KIND_ROUTES_NOT_COUNTED = 7;

    /**
     * Disables a module after its advice threw: {@link #KIND_LINKAGE_ERROR} for a {@link
     * LinkageError}, else {@link #KIND_ADVICE_FAILED}. This is the form every advice class calls.
     */
    public static void moduleFailed(String module, Throwable failure) {
        moduleFailed(module, KIND_ADVICE_FAILED, failure);
    }

    /**
     * Disables a module after {@code failure} was caught at the site {@code siteKind} names. A
     * {@link LinkageError} is {@link #KIND_LINKAGE_ERROR} whichever site caught it; any other
     * throwable keeps {@code siteKind}. The reason of a {@link LinkageError} is the error with its
     * message, which names a framework class or member. Any other reason is the exception's class
     * name alone, since its message can hold request text.
     */
    public static void moduleFailed(String module, int siteKind, Throwable failure) {
        int kind = failure instanceof LinkageError ? KIND_LINKAGE_ERROR : siteKind;
        String reason = failure instanceof LinkageError ? String.valueOf(failure) : failure == null ? "null" : failure.getClass().getName();
        disable(module, kind, reason);
    }

    /**
     * Disables a module for a reason that is no throwable, with {@code reason} as the text a person
     * reads. Same effect as {@link #moduleFailed(String, int, Throwable)}.
     */
    public static void moduleDisabled(String module, int kind, String reason) {
        disable(module, kind, reason);
    }

    private static void disable(String module, int kind, String reason) {
        if (module == null) return;
        if (!FAILURE_LOGGED.add(module)) return;
        DISABLED_MODULES.add(module);
        anyDisabled = true;
        LOG.log(Level.WARNING, "otherlode: endpoint module " + module + " disabled itself: " + reason);
        Resolver current = ready;
        if (current == null) {
            synchronized (BUFFER_LOCK) {
                current = resolver;
                if (current == null) {
                    buffer(BufferedRecord.forFailure(module, kind, reason));
                    return;
                }
            }
        }
        try {
            current.disableModule(module, kind, reason);
        } catch (Throwable t) {
            logDelegateFailure(module, "disableModule", t);
        }
    }

    /**
     * Sets the functional interfaces whose lambda classes {@link #recordLambdaClass} keeps, by
     * {@link Class#getName()}. An empty set turns recording off. The set replaces any earlier one.
     */
    public static void installHandlerInterfaces(Set<String> interfaceNames) {
        handlerInterfaces = interfaceNames == null || interfaceNames.isEmpty()
                ? Collections.<String>emptySet()
                : Collections.unmodifiableSet(new HashSet<>(interfaceNames));
    }

    /**
     * Remembers the method a lambda class calls, when the lambda is for a handler interface. A
     * lambda class for another interface, a method whose owner is hidden and an abstract method
     * are not kept. Entries are held weakly by the lambda class. This never throws.
     */
    public static void recordLambdaClass(Class<?> lambdaClass, Class<?> interfaceClass, MethodHandleInfo implementation) {
        // Runs inside the JDK's lambda factory. A lambda or method reference on this path would ask
        // the factory for a class while it is still building one and could recurse without end.
        try {
            if (lambdaClass == null || interfaceClass == null || implementation == null) return;
            if (!handlerInterfaces.contains(interfaceClass.getName())) return;
            Class<?> owner = implementation.getDeclaringClass();
            if (owner.isHidden()) return;
            if (Modifier.isAbstract(implementation.getModifiers())) return;
            LambdaImplementation found = new LambdaImplementation(
                    owner.getName(), implementation.getName(), implementation.getMethodType().toMethodDescriptorString());
            synchronized (LAMBDA_IMPLEMENTATIONS) {
                LAMBDA_IMPLEMENTATIONS.put(lambdaClass, found);
            }
        } catch (Throwable t) {
            if (!lambdaFailureLogged) {
                lambdaFailureLogged = true;
                LOG.log(Level.WARNING, "otherlode: could not record a handler lambda's method: " + t);
            }
        }
    }

    /**
     * Returns the method {@code lambdaClass} calls, as {@link #recordLambdaClass} recorded it, or
     * null when nothing was recorded for it. A miss is never filled in with a guess.
     */
    public static LambdaImplementation lambdaImplementation(Class<?> lambdaClass) {
        if (lambdaClass == null) return null;
        synchronized (LAMBDA_IMPLEMENTATIONS) {
            return LAMBDA_IMPLEMENTATIONS.get(lambdaClass);
        }
    }

    /** Whether this module is disabled, by {@link #moduleFailed}, {@link #moduleDisabled} or a full replay buffer. */
    public static boolean isDisabled(String module) {
        return isDisabledFast(module);
    }

    private static boolean isDisabledFast(String module) {
        return anyDisabled && module != null && DISABLED_MODULES.contains(module);
    }

    private static void buffer(BufferedRecord record) {
        if (BUFFER.size() >= BUFFER_CAPACITY && record.kind != RecordKind.FAILURE) {
            String module = record.module;
            if (module != null && FAILURE_LOGGED.add(module)) {
                DISABLED_MODULES.add(module);
                anyDisabled = true;
                BUFFER.addLast(BufferedRecord.forFailure(module, KIND_UNSPECIFIED, REPLAY_BUFFER_OVERFLOW));
                LOG.log(Level.WARNING, "otherlode: endpoint module " + module + " disabled itself: " + REPLAY_BUFFER_OVERFLOW);
            }
            if (!bufferOverflowLogged) {
                bufferOverflowLogged = true;
                LOG.log(
                        Level.WARNING,
                        "otherlode: endpoint replay buffer is full (" + BUFFER_CAPACITY + "); dropping new endpoint records"
                                + " until a resolver is installed");
            }
            return;
        }
        BUFFER.addLast(record);
    }

    private static void replay(Resolver target, BufferedRecord record) {
        try {
            switch (record.kind) {
                case REGISTER:
                    target.register(
                            record.key,
                            record.module,
                            record.verb,
                            record.verbatimTemplate,
                            record.contextPath,
                            record.handlerClass,
                            record.handlerMethod,
                            record.handlerDescriptor,
                            record.templateKind);
                    break;
                case DISPATCH:
                    Object entry =
                            target.recordDispatch(
                                    record.key,
                                    record.module,
                                    record.verb,
                                    record.verbatimTemplate,
                                    record.contextPath,
                                    record.handlerClass,
                                    record.templateKind);
                    if (entry != null) {
                        target.hit(entry);
                    }
                    break;
                case DISPATCH_IF_UNOWNED:
                    Object unownedEntry =
                            target.recordDispatchIfUnowned(
                                    record.key,
                                    record.module,
                                    record.verb,
                                    record.verbatimTemplate,
                                    record.contextPath,
                                    record.handlerClass,
                                    record.templateKind);
                    if (unownedEntry != null) {
                        target.hit(unownedEntry);
                    }
                    break;
                case DECLARE:
                    target.declare(record.module, record.frameworkObject);
                    break;
                case FAILURE:
                    target.disableModule(record.module, record.disableKind, record.reason);
                    break;
            }
        } catch (Throwable t) {
            logDelegateFailure(record.module, "replay", t);
        }
    }

    private static void logDelegateFailure(String module, String operation, Throwable t) {
        String key = operation + ":" + (module == null ? "(none)" : module);
        if (DELEGATE_FAILURE_LOGGED.add(key)) {
            LOG.log(Level.WARNING, "otherlode: endpoint resolver threw from " + operation + ": " + t, t);
        }
    }
}
