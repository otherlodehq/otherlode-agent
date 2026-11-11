package dev.otherlode.instrumentation.branch

import dev.otherlode.instrumentation.FixtureClassLoader
import java.io.File

// The fixture modules are never test dependencies, so JUnit discovery cannot load them before a
// test installs instrumentation.
object ScalaFixtures {
    private const val SCALA_TARGET_PACKAGE_PREFIX = "com.example.scalatarget."

    fun outputDir(module: String): File =
        File(
            System.getProperty("otherlode.fixtures.$module.dir")
                ?: error("system property otherlode.fixtures.$module.dir is not set; run tests through the root Gradle build"),
        )

    private fun runtimeClasspath(module: String): List<File> =
        System
            .getProperty("otherlode.fixtures.$module.classpath")
            ?.split(File.pathSeparator)
            ?.filter { it.isNotBlank() }
            ?.map { File(it) }
            ?: error("system property otherlode.fixtures.$module.classpath is not set; run tests through the root Gradle build")

    fun classBytes(
        module: String,
        simpleName: String,
    ): ByteArray = File(outputDir(module), "com/example/scalatarget/$simpleName.class").readBytes()

    fun classLoader(
        module: String,
        parent: ClassLoader,
        classes: File = outputDir(module),
    ): FixtureClassLoader {
        val urls = (listOf(classes) + runtimeClasspath(module)).map { it.toURI().toURL() }.toTypedArray()
        return FixtureClassLoader(urls, parent, SCALA_TARGET_PACKAGE_PREFIX)
    }
}
