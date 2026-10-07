package dev.otherlode.instrumentation.endpoints.api

import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.DynamicType
import net.bytebuddy.matcher.ElementMatcher
import net.bytebuddy.matcher.ElementMatchers.any

/**
 * One framework's registration and dispatch hooks, discovered with [java.util.ServiceLoader].
 *
 * A per-framework subproject (Spring MVC, Ktor, JAX-RS, the JDK's own `HttpServer`) implements
 * this to declare which types carry that framework's routing, and which advice turns a match into
 * a call into the endpoint seam. Endpoints are counted at the framework's own dispatch point
 * rather than inferred from the method tier, since one handler can back two endpoints, be a lambda
 * with no stable class name, or sit outside the include rules.
 */
interface EndpointModule {
    /** The framework id carried on the wire, and the module name the endpoint seam reports failures under. */
    val name: String

    /**
     * Boot-layer JDK module names this module instruments classes of, for example `"jdk.httpserver"`.
     * Each one is given a read edge to the endpoint seam's own module at install time: a named
     * module cannot otherwise read the seam, which lives on the bootstrap loader's unnamed module.
     */
    val bootModulesNeedingSeamRead: Set<String> get() = emptySet()

    /**
     * Functional interfaces, by `Class.getName()`, that this framework takes a handler as, for
     * example `"com.sun.net.httpserver.HttpHandler"`. A handler written as a lambda or a method
     * reference is a hidden class. When any module names an interface here, the agent watches the
     * JDK's lambda factory and records which method each lambda class for that interface calls.
     * The module's advice reads it back through `OtherlodeEndpoints.lambdaImplementation`. The method
     * tier also keeps a forwarder table for these interfaces, so a handler reported as a
     * pass-through joins to the method it forwards to.
     *
     * The lambda factory reports the interface the lambda was written for. A lambda for a
     * subinterface is recorded only when the subinterface is named here too.
     */
    val handlerInterfaces: Set<String> get() = emptySet()

    /**
     * Whether a failed weave of a class this module matched leaves the module on. The default is
     * false: the pipeline switches off every module whose matcher accepted a class that failed to
     * weave, since the hooks the weave would have added are lost.
     *
     * A module overrides this to true only when a class whose weave fails takes the module's own
     * staged declarations with it, so nothing partial is left. JAX-RS declares an adopter class's
     * routes while transforming that class and hooks only that class, so a failed weave drops both.
     */
    val weaveFailureLeavesNothingPartial: Boolean get() = false

    fun typeMatcher(): ElementMatcher<in TypeDescription>

    /**
     * Which class loaders this module can match a class on. The pipeline asks this before it asks
     * [typeMatcher], and a loader this rejects never has a class parsed for this module. A `null`
     * argument is the bootstrap loader, as in ByteBuddy's own matcher contract.
     *
     * A module whose framework lives only on some loaders overrides this with a check that costs
     * nothing per class, cached per loader, so that a loader without the framework costs no class
     * file read. The default accepts every loader.
     */
    fun classLoaderMatcher(): ElementMatcher<in ClassLoader> = any()

    /**
     * The form of [typeMatcher] the pipeline calls, given [classLoader], the loader defining the
     * class being matched (`null` for the bootstrap loader). A module that caches an answer per
     * loader overrides this, so the cache is keyed by the loader the class came from. The default
     * ignores the loader and returns [typeMatcher].
     */
    fun typeMatcher(classLoader: ClassLoader?): ElementMatcher<in TypeDescription> = typeMatcher()

    /**
     * Applies this module's advice to [builder] for [typeDescription].
     *
     * Advice is bound by class name through [advice], never referenced as a class literal: an
     * advice class compiled against the framework's own types does not resolve in the
     * classloader this module's own code runs in. See [AdviceBinder].
     *
     * [classLoader] is the classloader defining [typeDescription], null for the bootstrap loader.
     * A module that needs to know what else is present on that loader, such as a runtime library
     * whose presence changes what a class inherits, reads it directly rather than through
     * [advice], which only resolves advice bytecode.
     */
    fun transform(
        builder: DynamicType.Builder<*>,
        typeDescription: TypeDescription,
        advice: AdviceBinder,
        classLoader: ClassLoader?,
    ): DynamicType.Builder<*>

    /**
     * Called with a framework object a module's advice handed to `OtherlodeEndpoints.declare`, on the
     * agent's own loader; the module walks it, typically reflectively, and registers what it
     * finds through `OtherlodeEndpoints.register`.
     *
     * A framework whose routes are only readable from a registration hook's own arguments never
     * needs this and keeps the default no-op body. It exists for a framework such as Spring's
     * functional routing, whose routes are declared as a `RouterFunction` object that reveals its
     * routes only to a visitor implemented in real code, something advice itself cannot define.
     */
    fun declare(frameworkObject: Any) {}
}
