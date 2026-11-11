package dev.otherlode.instrumentation

import java.io.File

object JvmDefaultDisableFixtures {
    const val PACKAGE_PREFIX = "com.example.target.jvmdefaultdisable."

    val outputDir: File
        get() =
            File(
                System.getProperty("otherlode.fixtures.jvmdefaultdisable.dir")
                    ?: error("system property otherlode.fixtures.jvmdefaultdisable.dir is not set; run tests through the root Gradle build"),
            )

    private val runtimeClasspath: List<File>
        get() =
            System
                .getProperty("otherlode.fixtures.jvmdefaultdisable.classpath")
                ?.split(File.pathSeparator)
                ?.filter { it.isNotBlank() }
                ?.map { File(it) }
                ?: error("system property otherlode.fixtures.jvmdefaultdisable.classpath is not set; run tests through the root Gradle build")

    fun classBytes(simpleName: String): ByteArray = File(outputDir, "com/example/target/jvmdefaultdisable/$simpleName.class").readBytes()

    fun classLoader(parent: ClassLoader): FixtureClassLoader {
        val urls = (listOf(outputDir) + runtimeClasspath).map { it.toURI().toURL() }.toTypedArray()
        return FixtureClassLoader(urls, parent, PACKAGE_PREFIX)
    }
}
