package dev.otherlode.instrumentation

import java.net.URL
import java.net.URLClassLoader

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
