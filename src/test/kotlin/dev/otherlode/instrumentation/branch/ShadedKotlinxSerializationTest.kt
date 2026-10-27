package dev.otherlode.instrumentation.branch

import dev.otherlode.instrumentation.CompilerFixtures
import java.io.File
import java.lang.reflect.Proxy
import java.net.URLClassLoader
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Runs the kotlinx.serialization marks from the shaded agent jar. `shadowJar` rewrites every string
 * in the agent's own classes that starts with `kotlin`, `kotlinx` included, so a rule that spells
 * out a kotlinx name passes against the unshaded classes and marks nothing in the jar an adopter
 * runs. The jar is loaded apart from the test classpath, so every class here comes from the shaded
 * copy. The fixture bytes are real kotlinc output under the K1 frontend.
 */
class ShadedKotlinxSerializationTest {
    private val loader =
        URLClassLoader(
            arrayOf(
                File(
                    checkNotNull(System.getProperty("otherlode.agent.shadedJar")) { "otherlode.agent.shadedJar is not set" },
                ).toURI().toURL(),
            ),
            ClassLoader.getPlatformClassLoader(),
        )

    private val build = CompilerFixtures.kotlincSerialization(CompilerFixtures.kotlincSerializationKeys.first { it.endsWith("-k1") })

    @AfterTest
    fun close() = loader.close()

    private fun shadedFunction(
        arity: Int,
        answer: (Array<Any?>) -> Any?,
    ): Any {
        val type = loader.loadClass("dev.otherlode.shaded.kotlin.jvm.functions.Function$arity")
        return Proxy.newProxyInstance(loader, arrayOf(type)) { proxy, method, args ->
            when (method.name) {
                "invoke" -> answer(args ?: emptyArray())
                "equals" -> proxy === args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                else -> "shadedFunction$arity"
            }
        }
    }

    private fun shadedMarks(simpleName: String): Map<Pair<String, String>, String> {
        val analyzerClass = loader.loadClass("dev.otherlode.instrumentation.branch.BranchSiteAnalyzer")
        val analyzer = analyzerClass.getField("INSTANCE").get(null)
        val analyze = analyzerClass.methods.single { it.name == "analyze" && it.parameterCount == 10 }
        val analysis =
            analyze.invoke(
                analyzer,
                build.classBytes(simpleName),
                shadedFunction(1) { build.lookup(it[0] as String) },
                listOf("com.example.target"),
                emptyList<String>(),
                null,
                emptySet<String>(),
                shadedFunction(1) { null },
                null,
                false,
                shadedFunction(2) { true },
            )
        val generatedBy = analysis.javaClass.methods.single { it.name == "generatedBy" }
        return build.markEntries(simpleName).associate { (name, descriptor, _) ->
            (name to descriptor) to (generatedBy.invoke(analysis, name, descriptor) as Enum<*>).name
        }
    }

    @Test
    fun `the shaded analyser still marks a serializer class, a companion's serializer and a lazy lambda`() {
        assertEquals(
            setOf("KOTLINX_SERIALIZATION"),
            shadedMarks("SerPlain\$\$serializer").filterKeys { it.first != "<clinit>" }.values.toSet(),
        )
        assertEquals(
            "KOTLINX_SERIALIZATION",
            shadedMarks("SerPlain\$Companion")["serializer" to "()Lkotlinx/serialization/KSerializer;"],
        )
        assertEquals(setOf("KOTLINX_SERIALIZATION"), shadedMarks("SerSingleton\$1").values.toSet())
        assertEquals(
            "NONE",
            shadedMarks("SerNot\$Companion")["serializer" to "()Lkotlinx/serialization/KSerializer;"],
        )
    }
}
