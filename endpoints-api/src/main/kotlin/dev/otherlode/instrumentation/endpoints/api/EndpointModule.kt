package dev.otherlode.instrumentation.endpoints.api

import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.DynamicType
import net.bytebuddy.matcher.ElementMatcher
import net.bytebuddy.matcher.ElementMatchers.any

/**
 * One framework's endpoint hooks: which classes the module matches and the advice it applies to
 * them. An implementation is a [java.util.ServiceLoader] provider.
 */
interface EndpointModule {
    /**
     * The module's framework id, carried on the wire. Calls to `OtherlodeEndpoints` such as `declare`
     * and `moduleDisabled` name the module by this string.
     */
    val name: String

    /**
     * Names of boot-layer modules, such as `"jdk.httpserver"`, whose classes this module instruments
     * and that need to read the endpoint seam's module. A name the boot layer lacks is skipped. Empty
     * by default.
     */
    val bootModulesNeedingSeamRead: Set<String> get() = emptySet()

    /**
     * Functional interfaces, by `Class.getName()`, that this framework takes a handler as, such as
     * `"com.sun.net.httpserver.HttpHandler"`. The module's advice reads which method a lambda for one
     * of them calls through `OtherlodeEndpoints.lambdaImplementation`, which can return null. A lambda
     * for a subinterface is covered only when the subinterface is named too. Empty by default.
     */
    val handlerInterfaces: Set<String> get() = emptySet()

    /**
     * Whether a failed first weave of a class this module matched leaves the module on. Set it to true
     * when such a failure takes the module's staged declarations with it, so nothing partial is left.
     * A [transform] that throws switches the module off whatever this returns. False by default.
     */
    val weaveFailureLeavesNothingPartial: Boolean get() = false

    /**
     * Binary names (`Class.getName()` form) that include each class [typeMatcher] can accept on any
     * loader, or `null` when the module can match a class of any name. Null by default.
     */
    val typeNames: Set<String>? get() = null

    fun typeMatcher(): ElementMatcher<in TypeDescription>

    /**
     * Which class loaders this module can match a class on. A `null` argument is the bootstrap loader.
     * The default accepts any loader.
     */
    fun classLoaderMatcher(): ElementMatcher<in ClassLoader> = any()

    /**
     * A cheap check of the raw class file. Returning false skips the class for this module, so it
     * must return true for any class [typeMatcher] could accept. The default returns true.
     */
    fun classFileMayMatch(classFile: ByteArray): Boolean = true

    /**
     * The type matcher for classes that [classLoader] defines, where `null` is the bootstrap loader.
     * The default returns [typeMatcher].
     */
    fun typeMatcher(classLoader: ClassLoader?): ElementMatcher<in TypeDescription> = typeMatcher()

    /**
     * Applies this module's advice to [builder] for [typeDescription]. Bind advice by class name
     * through [advice], not as a class literal. [classLoader] defines [typeDescription] and is null
     * for the bootstrap loader.
     */
    fun transform(
        builder: DynamicType.Builder<*>,
        typeDescription: TypeDescription,
        advice: AdviceBinder,
        classLoader: ClassLoader?,
    ): DynamicType.Builder<*>

    /**
     * Receives a framework object passed to `OtherlodeEndpoints.declare` under this module's [name].
     * An implementation registers what it finds through `OtherlodeEndpoints.register`. The default
     * does nothing.
     */
    fun declare(frameworkObject: Any) {}

    /**
     * Called on the thread that flushes, before a manifest is built. [graceMillis] is the flush interval
     * in milliseconds, and [finalFlush] is true for the last flush. A module whose routes depend on a
     * condition can switch itself off here through `OtherlodeEndpoints.moduleDisabled` when the condition
     * never held. The default does nothing.
     */
    fun beforeManifest(
        graceMillis: Long,
        finalFlush: Boolean,
    ) {}
}
