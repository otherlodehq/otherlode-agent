package dev.otherlode.instrumentation.endpoints.api

import java.net.URL
import java.net.URLClassLoader

// PingTarget is defined here so that it is first defined after the transformer under test is installed.
class FixtureClassLoader(
    urls: Array<URL>,
    parent: ClassLoader,
) : URLClassLoader(urls, parent) {
    override fun loadClass(
        name: String,
        resolve: Boolean,
    ): Class<*> {
        if (name != TARGET_CLASS_NAME) return super.loadClass(name, resolve)
        synchronized(getClassLoadingLock(name)) {
            val existing = findLoadedClass(name)
            val loaded = existing ?: findClass(name)
            if (resolve) resolveClass(loaded)
            return loaded
        }
    }

    private companion object {
        const val TARGET_CLASS_NAME = "dev.otherlode.instrumentation.endpoints.api.fixture.PingTarget"
    }
}
