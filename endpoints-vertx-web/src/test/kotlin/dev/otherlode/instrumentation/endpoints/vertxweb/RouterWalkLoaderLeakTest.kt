package dev.otherlode.instrumentation.endpoints.vertxweb

import java.io.File
import java.lang.ref.Reference
import java.lang.ref.WeakReference
import java.net.URLClassLoader
import kotlin.test.Test
import kotlin.test.assertNull

/** A retired class loader must stay collectible after the walk has read a router defined in it. */
class RouterWalkLoaderLeakTest {
    private fun walkRouterInFreshLoader(walk: RouterWalk): WeakReference<ClassLoader> {
        val urls = System.getProperty("java.class.path").split(File.pathSeparator).map { File(it).toURI().toURL() }
        val loader = URLClassLoader(urls.toTypedArray(), ClassLoader.getPlatformClassLoader())
        val router =
            loader
                .loadClass(
                    "io.vertx.ext.web.Router",
                ).getMethod("router", loader.loadClass("io.vertx.core.Vertx"))
                .invoke(null, null)
        synchronized(walk) { walk.declare(router) }
        return WeakReference(loader)
    }

    @Test
    fun `a loader the walk read a router from is collected once nothing else holds it`() {
        val walk = RouterWalk(RouterGraph())
        val reference = walkRouterInFreshLoader(walk)

        repeat(40) {
            if (reference.get() != null) {
                System.gc()
                Thread.sleep(50)
            }
        }
        Reference.reachabilityFence(walk)
        assertNull(reference.get(), "the class loader is still reachable")
    }
}
