package dev.otherlode.instrumentation

import dev.otherlode.config.AgentConfig
import dev.otherlode.export.OutsideCaller
import dev.otherlode.export.OutsideCallerKind
import dev.otherlode.export.ProbeKind
import dev.otherlode.export.ProbeLocation
import dev.otherlode.export.ResourceAttributes
import dev.otherlode.instrumentation.branch.TypeHeader
import dev.otherlode.registry.ProbeRegistry
import net.bytebuddy.agent.ByteBuddyAgent
import net.bytebuddy.agent.builder.ResettableClassFileTransformer
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Proves that a METHOD probe's [OutsideCaller] reaches the manifest through the real transform
 * pipeline, on the `outsidecaller` fixtures and the `-jvm-default=disable` fixture module, and that
 * a class woven again keeps it.
 */
class OutsideCallerInstrumentationTest {
    private companion object {
        const val PACKAGE = "com.example.target.outsidecaller"
        const val EXTERNAL = "$PACKAGE.external"
        const val DISABLED_PACKAGE = "com.example.target.jvmdefaultdisable"
        val RESOURCE = ResourceAttributes("test", null, "instance-1", null, "run-1")

        fun overrides(typeName: String) = OutsideCaller(OutsideCallerKind.OVERRIDES_METHOD, typeName)
    }

    private val instrumentation = ByteBuddyAgent.install()
    private var installed: Pair<OtherlodeInstrumentation, ResettableClassFileTransformer>? = null

    @AfterTest
    fun tearDown() {
        installed?.let { (otherlode, transformer) -> otherlode.uninstall(instrumentation, transformer) }
        installed = null
    }

    private fun install(registry: ProbeRegistry) {
        val config = AgentConfig.parse("includePackages=$PACKAGE;$DISABLED_PACKAGE,excludePackages=$EXTERNAL;$PACKAGE.samepkg.Hidden")
        val otherlode = OtherlodeInstrumentation(config, registry)
        installed = otherlode to otherlode.install(instrumentation)
    }

    private fun fixtureLoader() =
        FixtureClassLoader(
            arrayOf(File("build/classes/java/test").toURI().toURL(), File("build/classes/kotlin/test").toURI().toURL()),
            javaClass.classLoader,
        )

    private fun methodProbes(
        registry: ProbeRegistry,
        className: String,
    ): List<ProbeLocation> = registry.manifest(RESOURCE).probes.filter { it.className == className && it.kind == ProbeKind.METHOD }

    private fun outsideCallerOf(
        probes: List<ProbeLocation>,
        name: String,
        descriptor: String? = null,
    ): OutsideCaller? = probes.single { it.methodName == name && (descriptor == null || it.methodDescriptor == descriptor) }.outsideCaller

    @Test
    fun `a METHOD probe of a method implementing Runnable carries the override, and a helper beside it carries none`() {
        val registry = ProbeRegistry()
        install(registry)

        Class.forName("$PACKAGE.RunnableImpl", true, fixtureLoader())

        val probes = methodProbes(registry, "$PACKAGE.RunnableImpl")
        assertEquals(overrides("java.lang.Runnable"), outsideCallerOf(probes, "run"))
        assertNull(outsideCallerOf(probes, "helper"))
        assertNull(outsideCallerOf(probes, "<init>"))
    }

    @Test
    fun `toString, equals and hashCode carry java lang Object`() {
        val registry = ProbeRegistry()
        install(registry)

        Class.forName("$PACKAGE.ObjectOverrides", true, fixtureLoader())

        val probes = methodProbes(registry, "$PACKAGE.ObjectOverrides")
        assertEquals(overrides("java.lang.Object"), outsideCallerOf(probes, "toString"))
        assertEquals(overrides("java.lang.Object"), outsideCallerOf(probes, "equals"))
        assertEquals(overrides("java.lang.Object"), outsideCallerOf(probes, "hashCode"))
        assertNull(outsideCallerOf(probes, "unrelated"))
    }

    @Test
    fun `a generic override carries the type through its bridge, and the bridge has no probe`() {
        val registry = ProbeRegistry()
        install(registry)

        Class.forName("$PACKAGE.Version", true, fixtureLoader())

        val probes = methodProbes(registry, "$PACKAGE.Version")
        assertEquals(overrides("java.lang.Comparable"), outsideCallerOf(probes, "compareTo"))
        assertEquals(1, probes.count { it.methodName == "compareTo" }, "the bridge is not probed")
    }

    @Test
    fun `implementations through an in-scope class, an anonymous class and a Kotlin object expression carry the override`() {
        val registry = ProbeRegistry()
        install(registry)
        val loader = fixtureLoader()

        listOf(
            "ConcreteEventHandler",
            "HandlerImpl",
            "Anonymous\$1",
            "KotlinObjectExpression\$make\$1",
            "ServerHandler",
            "samepkg.SamePackageChild",
        ).forEach { Class.forName("$PACKAGE.$it", true, loader) }

        assertEquals(
            overrides("$EXTERNAL.ExternalCallback"),
            outsideCallerOf(methodProbes(registry, "$PACKAGE.ConcreteEventHandler"), "onEvent"),
        )
        assertNull(outsideCallerOf(methodProbes(registry, "$PACKAGE.ConcreteEventHandler"), "tick"))
        assertEquals(overrides("java.lang.Runnable"), outsideCallerOf(methodProbes(registry, "$PACKAGE.HandlerImpl"), "run"))
        assertEquals(overrides("java.lang.Runnable"), outsideCallerOf(methodProbes(registry, "$PACKAGE.Anonymous\$1"), "run"))
        assertEquals(
            overrides("java.lang.Runnable"),
            outsideCallerOf(methodProbes(registry, "$PACKAGE.KotlinObjectExpression\$make\$1"), "run"),
        )
        assertEquals(
            overrides("com.sun.net.httpserver.HttpHandler"),
            outsideCallerOf(methodProbes(registry, "$PACKAGE.ServerHandler"), "handle"),
        )
        assertEquals(
            overrides("$PACKAGE.samepkg.Hidden"),
            outsideCallerOf(methodProbes(registry, "$PACKAGE.samepkg.SamePackageChild"), "quiet"),
        )
    }

    @Test
    fun `a DefaultImpls method carries the override only when its interface method overrides one`() {
        val registry = ProbeRegistry()
        install(registry)
        val loader = JvmDefaultDisableFixtures.classLoader(javaClass.classLoader)

        Class.forName("$DISABLED_PACKAGE.DisabledRunnableInterface\$DefaultImpls", true, loader)

        val probes = methodProbes(registry, "$DISABLED_PACKAGE.DisabledRunnableInterface\$DefaultImpls")
        assertEquals(overrides("java.lang.Runnable"), outsideCallerOf(probes, "run"))
        assertNull(outsideCallerOf(probes, "plain"))
    }

    @Test
    fun `a class woven again keeps the outside caller on its probes`() {
        val registry = ProbeRegistry()
        install(registry)
        val woven = Class.forName("$PACKAGE.RunnableImpl", true, fixtureLoader())
        val before = methodProbes(registry, "$PACKAGE.RunnableImpl")
        assertNotNull(outsideCallerOf(before, "run"))

        instrumentation.retransformClasses(woven)

        val after = methodProbes(registry, "$PACKAGE.RunnableImpl")
        assertEquals(before, after)
        assertEquals(overrides("java.lang.Runnable"), outsideCallerOf(after, "run"))
    }

    @Test
    fun `the bootstrap loader serves the class files of java types`() {
        val locator = ClassFileByteCache().locatorFor(null)

        val header = TypeHeader.parse(locator.locate("java.lang.Runnable").resolve())

        assertNotNull(header)
        assertTrue(header.declares("run()V", samePackage = false))
    }
}
