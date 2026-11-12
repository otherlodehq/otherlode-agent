package dev.otherlode.instrumentation.endpoints.api

import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.DynamicType
import net.bytebuddy.matcher.ElementMatcher
import net.bytebuddy.matcher.ElementMatchers.any

/**
 * One framework's registration and dispatch hooks, discovered with [java.util.ServiceLoader]. A
 * module declares which types carry the framework's routing and which advice turns a match into
 * a call into the endpoint seam.
 */
interface EndpointModule {
    /** The framework id carried on the wire, and the module name the endpoint seam reports failures under. */
    val name: String

    /**
     * Boot-layer module names this module instruments classes of, for example `"jdk.httpserver"`.
     * Each one the boot layer has is given a read edge to the endpoint seam's module at install
     * time. A name the boot layer lacks is skipped.
     */
    val bootModulesNeedingSeamRead: Set<String> get() = emptySet()

    /**
     * Functional interfaces, by `Class.getName()`, that this framework takes a handler as, for
     * example `"com.sun.net.httpserver.HttpHandler"`. When any module names one and the agent can
     * hook the JDK's lambda factory, the agent records which method each lambda class for it calls,
     * and the module's advice reads it through `OtherlodeEndpoints.lambdaImplementation`. A lambda
     * for a subinterface is recorded only when the subinterface is named too.
     */
    val handlerInterfaces: Set<String> get() = emptySet()

    /**
     * Whether a failed first weave of a class this module matched leaves the module on. The default
     * is false: the pipeline switches off every module whose matcher accepted a class that failed to
     * weave. A [transform] that throws, or a failed re-weave of a class whose first weave succeeded,
     * switches the module off either way. A module overrides this to true only when a failed weave
     * takes the module's own staged declarations with it, so nothing partial is left.
     */
    val weaveFailureLeavesNothingPartial: Boolean get() = false

    /**
     * The binary names (`Class.getName()` form) of every class [typeMatcher] can accept, for any
     * loader, or `null` when the module can match a class of any name. The pipeline uses the set to
     * skip other classes before it calls [typeMatcher].
     */
    val typeNames: Set<String>? get() = null

    fun typeMatcher(): ElementMatcher<in TypeDescription>

    /**
     * Which class loaders this module can match a class on. The pipeline asks this before
     * [typeMatcher]. A `null` argument is the bootstrap loader. The default accepts every loader.
     */
    fun classLoaderMatcher(): ElementMatcher<in ClassLoader> = any()

    /**
     * A cheap check of the raw class file, run after [classLoaderMatcher] accepts the loader and
     * before [typeMatcher]. Returning false skips the class for this module. It must return true
     * for every class [typeMatcher] could accept. The default accepts every class.
     */
    fun classFileMayMatch(classFile: ByteArray): Boolean = true

    /**
     * The form of [typeMatcher] the pipeline calls, given [classLoader], the loader defining the
     * class being matched (`null` for the bootstrap loader). The default returns [typeMatcher].
     */
    fun typeMatcher(classLoader: ClassLoader?): ElementMatcher<in TypeDescription> = typeMatcher()

    /**
     * Applies this module's advice to [builder] for [typeDescription]. Advice is bound by class
     * name through [advice], never as a class literal. See [AdviceBinder]. [classLoader] defines
     * [typeDescription] and is null for the bootstrap loader.
     */
    fun transform(
        builder: DynamicType.Builder<*>,
        typeDescription: TypeDescription,
        advice: AdviceBinder,
        classLoader: ClassLoader?,
    ): DynamicType.Builder<*>

    /**
     * Called with a framework object a module's advice handed to `OtherlodeEndpoints.declare`, on
     * the agent's own loader. The module walks it and registers what it finds through
     * `OtherlodeEndpoints.register`. The default does nothing.
     */
    fun declare(frameworkObject: Any) {}

    /**
     * Called on the thread that flushes, before the agent builds a manifest. A module that declares
     * routes only once a condition holds settles here whether it ever did, once [graceMillis], the
     * flush interval, has passed, or at once on the [finalFlush] before the agent stops. A module
     * that never saw the condition holds switches itself off through
     * `OtherlodeEndpoints.moduleDisabled`. The default does nothing.
     */
    fun beforeManifest(
        graceMillis: Long,
        finalFlush: Boolean,
    ) {}
}
