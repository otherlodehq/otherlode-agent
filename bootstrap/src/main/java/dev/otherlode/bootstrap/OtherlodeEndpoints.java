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
 * Records and counts HTTP endpoints through the {@link Resolver} given to {@link #install(Resolver)}.
 *
 * <p>Methods are safe to call from any thread, before or after {@link #install}. Before {@link
 * #install}, this class buffers a call for a module not yet disabled that records or declares an
 * endpoint or disables the module, and {@link #install} replays the buffer in order. When the
 * buffer is full, this class drops a call other than a disabling one and disables its module, and
 * still buffers a disabling call.
 *
 * <p>This class catches a throw from the resolver and logs it once per operation and module. A
 * method that returns an entry returns null when it has none, such as when the call is buffered, its
 * module is disabled or the resolver throws.
 */
public final class OtherlodeEndpoints {
    /** Template kind for a {@code verbatimTemplate} that is a path. */
    public static final int TEMPLATE_PATH = 0;

    /** Template kind for a {@code verbatimTemplate} that is the regular expression that matches a route. */
    public static final int TEMPLATE_REGEX = 1;

    /**
     * Template kind for a {@code verbatimTemplate} that is a servlet mapping no path can express,
     * such as {@code *.do} or the default mapping {@code /}.
     */
    public static final int TEMPLATE_SERVLET_PATTERN = 2;

    /**
     * What this class delegates to once installed: looking up, recording and counting endpoints, and
     * disabling modules.
     */
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
     * descriptor. The descriptor is the method's own, so it can start with values the lambda captured.
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
     * Installs the resolver, then replays the calls buffered before it in arrival order. A later call replaces
     * the resolver.
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

    /** Resolves a dispatch key to its endpoint entry, or null when there is none, such as before {@link #install}. */
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

    /** Records an endpoint with a path template and returns its entry. Buffers the call before {@link #install}. */
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
     * {@code templateKind} one of the {@code TEMPLATE_} constants, saying what {@code
     * verbatimTemplate} is.
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

    /**
     * Records an endpoint a request dispatched to and returns its entry, for {@link #hit}. Before
     * {@link #install} it buffers the call and returns null, and the replay counts one hit.
     */
    public static Object recordDispatch(
            String module, Object key, String verb, String verbatimTemplate, String contextPath, String handlerClass) {
        return recordDispatch(module, key, verb, verbatimTemplate, contextPath, handlerClass, TEMPLATE_PATH);
    }

    /**
     * Like {@link #recordDispatch(String, Object, String, String, String, String)}, for a template of
     * kind {@code templateKind}.
     */
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
     * Like {@link #recordDispatch}, for an endpoint no other module owns. Returns null and binds
     * nothing when another module owns it.
     */
    public static Object recordDispatchIfUnowned(
            String module, Object key, String verb, String verbatimTemplate, String contextPath, String handlerClass) {
        return recordDispatchIfUnowned(module, key, verb, verbatimTemplate, contextPath, handlerClass, TEMPLATE_PATH);
    }

    /**
     * Like {@link #recordDispatchIfUnowned(String, Object, String, String, String, String)}, for a
     * template of kind {@code templateKind}.
     */
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
     * Hands {@code frameworkObject} to the resolver for {@code module} to walk for routes. Records no
     * endpoint itself. Buffers the call before {@link #install}.
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
     * Maps each prefixed route back to the route it was built from.
     *
     * <p>Once the table holds {@code capacity} entries it adds no new route and keeps what it has,
     * though concurrent calls can each add one past it. A prefixed route recorded from two different
     * routes is ambiguous. Calls never throw.
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
         * Records that {@code unprefixed} was turned into {@code prefixed}. A null route or an empty
         * {@code unprefixed} records nothing. It records two equal routes as a pair too, so a different
         * pair for the same prefixed route makes it ambiguous in either order.
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
         * Follows {@code route} back through the recorded pairs to the route it was built from. Returns
         * {@code route} as given when a step is ambiguous or the chain has eight or more steps, and null
         * for null.
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
     * The most endpoints a module may create at dispatch, and the most keys of plain values, such as
     * strings, it may bind there.
     */
    public static final int DISPATCH_LIMIT = 8192;

    private static final RoutePrefixTable ROUTE_PREFIXES = new RoutePrefixTable(8192);

    /**
     * Returns {@code method} when it is one of GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS and
     * TRACE, else {@code *}, also for null.
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

    /**
     * Records in a shared {@link RoutePrefixTable} that {@code unprefixed} was turned into {@code
     * prefixed}. Never throws.
     */
    public static void recordRoutePrefix(String prefixed, String unprefixed) {
        try {
            ROUTE_PREFIXES.record(prefixed, unprefixed);
        } catch (Throwable t) {
            logDelegateFailure(null, "recordRoutePrefix", t);
        }
    }

    /**
     * Resolves {@code route} against the pairs {@link #recordRoutePrefix} recorded, as {@link
     * RoutePrefixTable#resolve} does. Never throws.
     */
    public static String resolveRoutePrefix(String route) {
        try {
            return ROUTE_PREFIXES.resolve(route);
        } catch (Throwable t) {
            logDelegateFailure(null, "resolveRoutePrefix", t);
            return route;
        }
    }

    /**
     * Passes one hit on {@code entry} to the resolver. Adds no synchronisation, so a concurrent hit can
     * be lost unless the resolver guards its count. Does nothing for a null entry or before {@link #install}.
     */
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
     * Marks {@code request} on this thread for {@link #takeResourceServed}, in place of any earlier
     * mark. Holds {@code request} weakly.
     */
    public static void resourceServed(Object request) {
        RESOURCE_SERVED.set(new WeakReference<>(request));
    }

    /**
     * Whether {@link #resourceServed} marked {@code request} on this thread. It clears any mark on
     * this thread either way.
     */
    public static boolean takeResourceServed(Object request) {
        WeakReference<Object> mark = RESOURCE_SERVED.get();
        if (mark == null) return false;
        RESOURCE_SERVED.remove();
        return mark.get() == request;
    }

    /**
     * Returns a consumer to run on each resource a reactive resources route emits. It marks {@code
     * exchange} through {@link #resourceServed}, then counts a hit on {@code entry}. A null {@code
     * entry} means the endpoint is not yet known, so the consumer looks {@code key} up and records a
     * dispatch under {@code verb} and {@code template} if that finds nothing. A null {@code key}
     * means the request is no call, such as a preflight, and counts nothing. The consumer never throws.
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
     * A scope {@link #enterRegistrationScope} opened: the object that owns the handlers registered in
     * it, and the two paths they are registered under.
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
     * Opens a scope on this thread. {@link #registrationScope} returns the innermost scope still open. The
     * caller pairs each call with {@link #exitRegistrationScope}, also when its own work throws. This
     * never throws.
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

    /**
     * Asks the resolver to attach a handler class, method and descriptor to {@code entry}. Does
     * nothing for a null entry, a disabled module or before {@link #install}.
     */
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

    /** Disable kind for a reason that fits no other kind, such as a full replay buffer. */
    public static final int KIND_UNSPECIFIED = 0;

    /** Disable kind for a {@link LinkageError}, or a framework without the API the module was built for. */
    public static final int KIND_LINKAGE_ERROR = 1;

    /**
     * Disable kind for a throw other than a {@link LinkageError} from a module's advice or its
     * {@code beforeManifest}, or a value its advice cannot use.
     */
    public static final int KIND_ADVICE_FAILED = 2;

    /**
     * Disable kind for a failure while matching or weaving a class for a module, such as a throw from
     * the module's matcher or transform.
     */
    public static final int KIND_TRANSFORM_FAILED = 3;

    /** Disable kind for a throw while walking a framework's route objects. */
    public static final int KIND_ROUTE_WALK_FAILED = 4;

    /** Disable kind for a hook that matched no method with a body on the framework class it hooks. */
    public static final int KIND_HOOK_UNMATCHED = 5;

    /** Disable kind for routes a module found but cannot count, such as when no dispatch point was built in time. */
    public static final int KIND_ROUTES_NOT_COUNTED = 7;

    /**
     * Disables a module after its advice threw: {@link #KIND_LINKAGE_ERROR} for a {@link
     * LinkageError}, else {@link #KIND_ADVICE_FAILED}.
     */
    public static void moduleFailed(String module, Throwable failure) {
        moduleFailed(module, KIND_ADVICE_FAILED, failure);
    }

    /**
     * Disables a module after {@code failure} was caught at the site {@code siteKind} names. A
     * {@link LinkageError} is {@link #KIND_LINKAGE_ERROR} whichever site caught it; any other
     * throwable keeps {@code siteKind}. The reason is a {@link LinkageError}'s {@code toString()}, or
     * any other throwable's class name alone. Does nothing for a null or already disabled module.
     */
    public static void moduleFailed(String module, int siteKind, Throwable failure) {
        int kind = failure instanceof LinkageError ? KIND_LINKAGE_ERROR : siteKind;
        String reason = failure instanceof LinkageError ? String.valueOf(failure) : failure == null ? "null" : failure.getClass().getName();
        disable(module, kind, reason);
    }

    /**
     * Disables a module for a reason that is no throwable, with {@code reason} as the reason logged
     * and passed to the resolver. Same effect as {@link #moduleFailed(String, int, Throwable)}.
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
     * {@link Class#getName()}. A null or empty set turns recording off. The set replaces any earlier one.
     */
    public static void installHandlerInterfaces(Set<String> interfaceNames) {
        handlerInterfaces = interfaceNames == null || interfaceNames.isEmpty()
                ? Collections.<String>emptySet()
                : Collections.unmodifiableSet(new HashSet<>(interfaceNames));
    }

    /**
     * Remembers the method a lambda class calls, when the lambda is for a handler interface set by
     * {@link #installHandlerInterfaces}. Keeps nothing in some cases, such as a hidden owner or an
     * abstract method. Holds the lambda class weakly. This never throws.
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
     * null when nothing was recorded for it.
     */
    public static LambdaImplementation lambdaImplementation(Class<?> lambdaClass) {
        if (lambdaClass == null) return null;
        synchronized (LAMBDA_IMPLEMENTATIONS) {
            return LAMBDA_IMPLEMENTATIONS.get(lambdaClass);
        }
    }

    /** Whether {@code module} is disabled, such as by {@link #moduleFailed} or a full replay buffer. */
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
