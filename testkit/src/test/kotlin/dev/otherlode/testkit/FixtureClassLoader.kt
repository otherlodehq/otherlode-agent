package dev.otherlode.testkit

import java.net.URL
import java.net.URLClassLoader

class FixtureClassLoader(
    urls: Array<URL>,
    parent: ClassLoader,
    private val packagePrefix: String = "com.example.testkittarget.",
) : URLClassLoader(urls, parent) {
    override fun loadClass(
        name: String,
        resolve: Boolean,
    ): Class<*> {
        if (!name.startsWith(packagePrefix)) return super.loadClass(name, resolve)
        synchronized(getClassLoadingLock(name)) {
            val existing = findLoadedClass(name)
            val loaded = existing ?: findClass(name)
            if (resolve) resolveClass(loaded)
            return loaded
        }
    }
}
