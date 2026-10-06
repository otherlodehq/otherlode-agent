package dev.otherlode.instrumentation

import dev.otherlode.config.AgentConfig
import dev.otherlode.export.OutsideCaller
import dev.otherlode.export.OutsideCallerKind
import dev.otherlode.export.ProbeKind
import dev.otherlode.export.ProbeLocation
import dev.otherlode.export.ResourceAttributes
import dev.otherlode.registry.ProbeRegistry
import net.bytebuddy.agent.ByteBuddyAgent
import net.bytebuddy.agent.builder.ResettableClassFileTransformer
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Proves that a METHOD probe's callback-annotation [OutsideCaller] reaches the manifest through the
 * real transform pipeline, on the `callbacks` fixtures, and that a class woven again keeps it.
 */
class CallbackAnnotationInstrumentationTest {
    private companion object {
        const val PACKAGE = "com.example.target.callbacks"
        const val LISTENERS = "$PACKAGE.Listeners"
        val RESOURCE = ResourceAttributes("test", null, "instance-1", null, "run-1")

        fun annotated(typeName: String) = OutsideCaller(OutsideCallerKind.CALLBACK_ANNOTATION, typeName)
    }

    private val instrumentation = ByteBuddyAgent.install()
    private var installed: Pair<OtherlodeInstrumentation, ResettableClassFileTransformer>? = null

    @AfterTest
    fun tearDown() {
        installed?.let { (otherlode, transformer) -> otherlode.uninstall(instrumentation, transformer) }
        installed = null
    }

    private fun weave(registry: ProbeRegistry): Class<*> {
        val otherlode = OtherlodeInstrumentation(AgentConfig.parse("includePackages=$PACKAGE"), registry)
        installed = otherlode to otherlode.install(instrumentation)
        val loader =
            FixtureClassLoader(
                arrayOf(File("build/classes/java/test").toURI().toURL(), File("build/classes/kotlin/test").toURI().toURL()),
                javaClass.classLoader,
            )
        return Class.forName(LISTENERS, true, loader)
    }

    private fun probes(registry: ProbeRegistry): List<ProbeLocation> =
        registry.manifest(RESOURCE).probes.filter { it.className == LISTENERS && it.kind == ProbeKind.METHOD }

    private fun outsideCallerOf(
        probes: List<ProbeLocation>,
        name: String,
    ): OutsideCaller? = probes.single { it.methodName == name }.outsideCaller

    @Test
    fun `a method with a listed annotation carries it, and an override beside it loses to it`() {
        val registry = ProbeRegistry()
        weave(registry)

        val probes = probes(registry)
        assertEquals(annotated("org.springframework.context.event.EventListener"), outsideCallerOf(probes, "onEvent"))
        assertEquals(annotated("org.springframework.scheduling.annotation.Scheduled"), outsideCallerOf(probes, "run"))
        assertEquals(annotated("com.example.target.callbacks.DeepComposed"), outsideCallerOf(probes, "deep"))
        assertEquals(annotated("jakarta.enterprise.event.Observes"), outsideCallerOf(probes, "observe"))
        assertEquals(annotated("org.springframework.context.annotation.Bean"), outsideCallerOf(probes, "bean"))
    }

    @Test
    fun `unlisted, constructor and parameter-only ModelAttribute methods carry none`() {
        val registry = ProbeRegistry()
        weave(registry)

        val probes = probes(registry)
        assertNull(outsideCallerOf(probes, "unlisted"))
        assertNull(outsideCallerOf(probes, "classRetained"))
        assertNull(outsideCallerOf(probes, "modelOnParameter"))
        assertNull(outsideCallerOf(probes, "<init>"))
    }

    @Test
    fun `a class woven again keeps the callback annotation`() {
        val registry = ProbeRegistry()
        val woven = weave(registry)
        val before = probes(registry)

        instrumentation.retransformClasses(woven)

        val after = probes(registry)
        assertEquals(before, after)
        assertEquals(annotated("org.springframework.context.event.EventListener"), outsideCallerOf(after, "onEvent"))
    }
}
