package dev.otherlode.instrumentation

import java.net.URL
import java.net.URLClassLoader

/**
 * Loads anything under [fixturePackagePrefix] itself, instead of delegating to the parent. This
 * way, the fixture is defined for the first time only after instrumentation is installed.
 *
 * A resource under the same prefix is looked up here first too, so the class file the agent reads
 * through this loader is the one this loader defines the class from, even when the parent's
 * classpath carries a different class file under the same name.
 *
 * Everything else, such as the JDK and the agent classes, still resolves through the parent as
 * normal.
 */
open class FixtureClassLoader(
    urls: Array<URL>,
    parent: ClassLoader,
    private val fixturePackagePrefix: String = "com.example.target.",
) : URLClassLoader(urls, parent) {
    override fun loadClass(
        name: String,
        resolve: Boolean,
    ): Class<*> {
        if (!name.startsWith(fixturePackagePrefix)) return super.loadClass(name, resolve)
        synchronized(getClassLoadingLock(name)) {
            val existing = findLoadedClass(name)
            val loaded = existing ?: findClass(name)
            if (resolve) resolveClass(loaded)
            return loaded
        }
    }

    override fun getResource(name: String): URL? {
        if (!name.startsWith(fixturePackagePrefix.replace('.', '/'))) return super.getResource(name)
        return findResource(name) ?: super.getResource(name)
    }
}
