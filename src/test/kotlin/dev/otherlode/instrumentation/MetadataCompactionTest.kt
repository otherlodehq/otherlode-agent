package dev.otherlode.instrumentation

import dev.otherlode.config.AgentConfig
import dev.otherlode.export.ProbeKind
import dev.otherlode.export.ResourceAttributes
import dev.otherlode.registry.ProbeRegistry
import net.bytebuddy.agent.ByteBuddyAgent
import net.bytebuddy.agent.builder.ResettableClassFileTransformer
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Pins the two rules metadata is built with: repeated names share one instance, and held lists carry no slack. */
class MetadataCompactionTest {
    private var installedTransformer: ResettableClassFileTransformer? = null
    private var installedOtherlode: OtherlodeInstrumentation? = null

    @AfterTest
    fun tearDown() {
        installedTransformer?.let { installedOtherlode?.uninstall(ByteBuddyAgent.install(), it) }
        installedTransformer = null
        installedOtherlode = null
    }

    @Test
    fun `interned returns the canonical instance of an equal string built at runtime`() {
        val built = StringBuilder("com.x.").append("Name").toString()
        assertNotSame("com.x.Name".intern(), built)
        assertSame("com.x.Name".intern(), built.interned())
        assertSame("com.x.Name".intern(), built.internedOrNull())
        assertEquals(null, (null as String?).internedOrNull())
    }

    @Test
    fun `internedAll interns every element and shares the empty list`() {
        val first = String(charArrayOf('a', 'b', 'c'))
        val second = String(charArrayOf('d', 'e', 'f'))
        val both = listOf(first, second).internedAll()
        assertEquals(listOf("abc", "def"), both)
        assertSame("abc".intern(), both[0])
        assertSame("def".intern(), both[1])
        assertSame("abc".intern(), listOf(String(charArrayOf('a', 'b', 'c'))).internedAll().single())
        assertSame(emptyList<String>(), ArrayList<String>().internedAll())
    }

    @Test
    fun `rightSized shares the empty list, makes a single element list and copies the rest to an exact size`() {
        assertSame(emptyList<Int>(), ArrayList<Int>(64).rightSized())
        assertEquals(listOf(7), ArrayList<Int>(64).apply { add(7) }.rightSized())
        assertEquals(
            java.util.Collections
                .singletonList(7)
                .javaClass,
            ArrayList<Int>(64).apply { add(7) }.rightSized().javaClass,
        )
        assertEquals(listOf(1, 2, 3), ArrayList<Int>(64).apply { addAll(listOf(1, 2, 3)) }.rightSized())
    }

    @Test
    fun `two woven classes naming one callee share its class name, method name and descriptor`() {
        val manifest = weaveFixtures()
        val first = manifest.methodProbe("CompactionFirst", "run")
        val second = manifest.methodProbe("CompactionSecond", "run")
        val firstEdge = first.calls.single { it.className.endsWith("CompactionShared") && it.methodName == "shared" }
        val secondEdge = second.calls.single { it.className.endsWith("CompactionShared") && it.methodName == "shared" }
        assertSame(firstEdge.className, secondEdge.className)
        assertSame(firstEdge.methodName, secondEdge.methodName)
        assertSame(firstEdge.methodDescriptor, secondEdge.methodDescriptor)
        assertSame(first.methodName, second.methodName)
        assertSame(first.methodDescriptor, second.methodDescriptor)
    }

    @Test
    fun `a probe with no call edges and no references holds the shared empty lists`() {
        val manifest = weaveFixtures()
        val leaf = manifest.methodProbe("CompactionFirst", "leaf")
        assertSame(emptyList(), leaf.calls)
        assertSame(emptyList(), leaf.referencedClasses)
        assertSame(emptyList(), leaf.parameterNames)
        val branches = manifest.probes.filter { it.kind == ProbeKind.BRANCH && it.className.startsWith("com.example.target.Compaction") }
        assertTrue(branches.all { it.calls === emptyList<Any>() })
    }

    private fun dev.otherlode.export.ProbeManifest.methodProbe(
        simpleName: String,
        method: String,
    ) = probes.single {
        it.className == "com.example.target.$simpleName" && it.methodName == method && it.kind == ProbeKind.METHOD
    }

    private fun weaveFixtures(): dev.otherlode.export.ProbeManifest {
        val registry = ProbeRegistry()
        val instrumentation = ByteBuddyAgent.install()
        val otherlode = OtherlodeInstrumentation(AgentConfig.parse("includePackages=com.example.target"), registry)
        installedOtherlode = otherlode
        installedTransformer = otherlode.install(instrumentation)
        val loader = FixtureClassLoader(arrayOf(File("build/classes/kotlin/test").toURI().toURL()), javaClass.classLoader)
        for (name in listOf("CompactionFirst", "CompactionSecond")) Class.forName("com.example.target.$name", true, loader)
        return registry.manifest(ResourceAttributes("test", null, "instance-1", null, "run-1"))
    }
}
