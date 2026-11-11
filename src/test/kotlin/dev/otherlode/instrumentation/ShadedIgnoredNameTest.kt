package dev.otherlode.instrumentation

import java.io.File
import java.net.URLClassLoader
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShadedIgnoredNameTest {
    private val loader =
        URLClassLoader(
            arrayOf(
                File(
                    checkNotNull(System.getProperty("otherlode.agent.shadedJar")) { "otherlode.agent.shadedJar is not set" },
                ).toURI().toURL(),
            ),
            ClassLoader.getPlatformClassLoader(),
        )

    @AfterTest
    fun close() = loader.close()

    private fun shadedIgnores(className: String): Boolean {
        val policy = loader.loadClass("dev.otherlode.instrumentation.TypeMatchPolicy")
        val instance = policy.getField("INSTANCE").get(null)
        return policy.getMethod("isIgnoredName", String::class.java).invoke(instance, className) as Boolean
    }

    @Test
    fun `the shaded jar ignores an application's own net bytebuddy classes`() {
        assertTrue(shadedIgnores(listOf("net", "bytebuddy", "Something").joinToString(".")))
        assertTrue(shadedIgnores("sun.reflect.Something"))
        assertTrue(shadedIgnores("jdk.internal.reflect.Something"))
    }

    @Test
    fun `the shaded jar does not ignore the agent's relocated names or an application's classes`() {
        assertFalse(shadedIgnores("dev.otherlode.shaded.bytebuddy.Something"))
        assertFalse(shadedIgnores("com.acme.Something"))
    }
}
