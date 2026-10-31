package dev.otherlode.instrumentation

import dev.otherlode.bootstrap.OtherlodeProbeArrays
import java.util.WeakHashMap

/**
 * Finds the class loaders that cannot resolve `dev.otherlode.bootstrap`. A woven class names
 * [OtherlodeProbeArrays] and resolves it through its defining loader, so a loader that does not
 * delegate that package to the bootstrap loader (JBoss Modules without `jboss.modules.system.pkgs`,
 * Apache Felix without `org.osgi.framework.bootdelegation`) fails the class at its first probe.
 *
 * The answer is cached once per loader, weakly, and holds no reference to the loader. It is read at
 * transform time only. One instance serves both tiers, so each loader gets one WARNING.
 */
class BootstrapVisibilityGuard {
    private val log = System.getLogger(BootstrapVisibilityGuard::class.java.name)
    private val visible = WeakHashMap<ClassLoader, Boolean>()

    /**
     * The reason [classLoader] cannot weave classes, or null when it can. The first call that finds
     * a loader blind to the holder logs the one WARNING for that loader.
     */
    fun refusal(classLoader: ClassLoader?): String? {
        if (classLoader == null) return null
        val known = synchronized(visible) { visible[classLoader] }
        if (known == true) return null
        if (known == null) {
            val sees = sees(classLoader)
            val first = synchronized(visible) { visible.putIfAbsent(classLoader, sees) == null }
            if (sees) return null
            if (first) log.log(System.Logger.Level.WARNING, warning(classLoader))
        }
        return REASON
    }

    private fun sees(classLoader: ClassLoader): Boolean =
        try {
            Class.forName(BootstrapHolder.HOLDER_CLASS_NAME, false, classLoader) === OtherlodeProbeArrays::class.java
        } catch (_: Throwable) {
            false
        }

    private fun warning(classLoader: ClassLoader): String =
        "otherlode: the class loader ${classLoader.javaClass.name} cannot see dev.otherlode.bootstrap, " +
            "so the agent does not instrument the classes it defines; delegate dev.otherlode.bootstrap to the boot loader, " +
            "for example with -Djboss.modules.system.pkgs=dev.otherlode.bootstrap " +
            "or org.osgi.framework.bootdelegation=dev.otherlode.bootstrap"

    private companion object {
        const val REASON = "its class loader cannot see dev.otherlode.bootstrap, so it is not instrumented"
    }
}
