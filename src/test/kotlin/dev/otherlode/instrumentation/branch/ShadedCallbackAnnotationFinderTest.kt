package dev.otherlode.instrumentation.branch

import java.io.File
import java.lang.reflect.Proxy
import java.net.URLClassLoader
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ShadedCallbackAnnotationFinderTest {
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

    private fun classFilesRead(annotations: List<String>): List<String> {
        val read = mutableListOf<String>()
        val function1 = loader.loadClass("dev.otherlode.shaded.kotlin.jvm.functions.Function1")
        val readBytes =
            Proxy.newProxyInstance(loader, arrayOf(function1)) { proxy, method, args ->
                when (method.name) {
                    "invoke" -> {
                        read += args!![0] as String
                        null
                    }

                    "equals" -> proxy === args?.get(0)
                    "hashCode" -> System.identityHashCode(proxy)
                    else -> "readBytes"
                }
            }
        val configuredType = loader.loadClass("dev.otherlode.instrumentation.branch.ConfiguredCallbackAnnotations")
        val configured = configuredType.getConstructor(List::class.java).newInstance(emptyList<Any>())
        val finderType = loader.loadClass("dev.otherlode.instrumentation.branch.CallbackAnnotationFinder")
        val cacheType = loader.loadClass("dev.otherlode.instrumentation.branch.BranchSiteAnalyzer\$CrossClassTableCache")
        val finder = finderType.getConstructor(function1, cacheType, configuredType).newInstance(readBytes, null, configured)
        val relationType = loader.loadClass("dev.otherlode.instrumentation.Relation")
        val superclass = relationType.enumConstants.single { (it as Enum<*>).name == "SUPERCLASS" }
        val inheritable = finderType.methods.single { it.name == "inheritable" }
        inheritable.invoke(finder, annotations, emptyList<String>(), superclass, false)
        return read
    }

    @Test
    fun `the shaded finder reads the class file of an annotation that is not skipped`() {
        assertEquals(listOf("com/example/Marker"), classFilesRead(listOf("com/example/Marker")))
    }

    @Test
    fun `the shaded finder skips the kotlin annotation package as the unshaded finder does`() {
        assertEquals(emptyList(), classFilesRead(listOf("kotlin/annotation/Target", "kotlin/annotation/Retention")))
        assertEquals(emptyList(), classFilesRead(listOf("java/lang/annotation/Documented")))
    }
}
