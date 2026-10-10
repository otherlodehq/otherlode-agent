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
 * Proves that a METHOD probe's outside caller follows its own callback annotation, then one
 * inherited from a method it overrides, then an out-of-scope override, through the real transform
 * pipeline on the `inherit` fixtures.
 */
class InheritedCallbackAnnotationInstrumentationTest {
    private companion object {
        const val PACKAGE = "com.example.target.inherit"
        const val OUTSIDE_API = "com.example.outside.inherit.OutsideApi"
        val RESOURCE = ResourceAttributes("test", null, "instance-1", null, "run-1")

        fun annotated(typeName: String) = OutsideCaller(OutsideCallerKind.CALLBACK_ANNOTATION, typeName)

        fun overrides(typeName: String) = OutsideCaller(OutsideCallerKind.OVERRIDES_METHOD, typeName)
    }

    private val instrumentation = ByteBuddyAgent.install()
    private var installed: Pair<OtherlodeInstrumentation, ResettableClassFileTransformer>? = null

    @AfterTest
    fun tearDown() {
        installed?.let { (otherlode, transformer) -> otherlode.uninstall(instrumentation, transformer) }
        installed = null
    }

    private fun manifest(
        vararg classNames: String,
        options: String = "",
    ): List<ProbeLocation> {
        val registry = ProbeRegistry()
        val otherlode = OtherlodeInstrumentation(AgentConfig.parse("includePackages=$PACKAGE$options"), registry)
        installed = otherlode to otherlode.install(instrumentation)
        val loader =
            FixtureClassLoader(
                arrayOf(File("build/classes/java/test").toURI().toURL(), File("build/classes/kotlin/test").toURI().toURL()),
                javaClass.classLoader,
            )
        classNames.forEach { Class.forName("$PACKAGE.$it", true, loader) }
        return registry.manifest(RESOURCE).probes.filter { it.kind == ProbeKind.METHOD }
    }

    private fun outsideCallerOf(
        probes: List<ProbeLocation>,
        className: String,
        method: String,
        descriptor: String? = null,
    ): OutsideCaller? =
        probes
            .single {
                it.className == "$PACKAGE.$className" && it.methodName == method &&
                    (descriptor == null || it.methodDescriptor == descriptor)
            }.outsideCaller

    @Test
    fun `an unannotated override is labelled by the annotation on the method it overrides`() {
        val probes = manifest("InheritImpl")

        assertEquals(
            annotated("org.springframework.web.bind.annotation.GetMapping"),
            outsideCallerOf(probes, "InheritImpl", "fromInterface"),
        )
        assertEquals(annotated("org.springframework.context.event.EventListener"), outsideCallerOf(probes, "InheritImpl", "fromSuperclass"))
        assertEquals(annotated("org.springframework.context.event.EventListener"), outsideCallerOf(probes, "InheritImpl", "fromDefault"))
        assertEquals(
            annotated("org.springframework.context.event.EventListener"),
            outsideCallerOf(probes, "InheritImpl", "handle", "(Ljava/lang/String;)V"),
        )
    }

    @Test
    fun `an annotation that its framework does not honour from a supertype leaves the override unlabelled`() {
        val probes = manifest("InheritImpl")

        assertNull(outsideCallerOf(probes, "InheritImpl", "scheduledInterface"))
        assertNull(outsideCallerOf(probes, "InheritImpl", "scheduledSuper"))
        assertNull(outsideCallerOf(probes, "InheritImpl", "postConstructSuper"))
        assertNull(outsideCallerOf(probes, "InheritImpl", "beanAbstract"))
    }

    @Test
    fun `the method's own annotation beats an inherited one`() {
        val probes = manifest("OwnWins")

        assertEquals(annotated("org.springframework.scheduling.annotation.Scheduled"), outsideCallerOf(probes, "OwnWins", "fromInterface"))
        assertEquals(annotated("org.springframework.web.bind.annotation.GetMapping"), outsideCallerOf(probes, "OwnWins", "openApi"))
    }

    @Test
    fun `an inherited annotation beats an out-of-scope override, which stays when nothing is inherited`() {
        val probes = manifest("OutsideImpl")

        assertEquals(annotated("org.springframework.context.event.EventListener"), outsideCallerOf(probes, "OutsideImpl", "listener"))
        assertEquals(overrides(OUTSIDE_API), outsideCallerOf(probes, "OutsideImpl", "scheduled"))
        assertEquals(overrides(OUTSIDE_API), outsideCallerOf(probes, "OutsideImpl", "plain"))
    }

    @Test
    fun `the nearest supertype wins`() {
        val probes = manifest("FromSuperclassFirst", "FromFirstInterface", "FromSecondInterface")

        assertEquals(annotated("org.springframework.context.event.EventListener"), outsideCallerOf(probes, "FromSuperclassFirst", "m"))
        assertEquals(annotated("org.springframework.web.bind.annotation.GetMapping"), outsideCallerOf(probes, "FromFirstInterface", "m"))
        assertEquals(
            annotated("org.springframework.web.bind.annotation.ModelAttribute"),
            outsideCallerOf(probes, "FromSecondInterface", "m"),
        )
    }

    @Test
    fun `a named annotation on a supertype method labels the override and a configured name is required`() {
        val names = "com.example.outside.named.RuntimeCall;com.example.outside.named.ClassCall"

        val withOption = manifest("NamedImpl", options = ",callbackAnnotations=$names")

        assertEquals(annotated("com.example.outside.named.RuntimeCall"), outsideCallerOf(withOption, "NamedImpl", "runtime"))
        assertEquals(annotated("com.example.outside.named.ClassCall"), outsideCallerOf(withOption, "NamedImpl", "classRetained"))
        tearDown()
        val without = manifest("NamedImpl")
        assertNull(outsideCallerOf(without, "NamedImpl", "runtime"))
        assertNull(outsideCallerOf(without, "NamedImpl", "classRetained"))
    }

    @Test
    fun `a workflow annotation labels an implementation from its interface and never on the implementation itself`() {
        val probes = manifest("WfImpl", "WfOwn", "WfComposedImpl")

        assertEquals(annotated("io.temporal.workflow.WorkflowMethod"), outsideCallerOf(probes, "WfImpl", "run"))
        assertEquals(annotated("io.temporal.workflow.SignalMethod"), outsideCallerOf(probes, "WfImpl", "signal"))
        assertNull(outsideCallerOf(probes, "WfOwn", "run"))
        assertNull(outsideCallerOf(probes, "WfComposedImpl", "run"))
    }

    @Test
    fun `an activity interface labels its methods, and beats an out-of-scope override`() {
        val probes = manifest("ActImpl", "ActChildImpl", "ActLooseImpl", "ActOutsideImpl")

        assertEquals(annotated("io.temporal.activity.ActivityInterface"), outsideCallerOf(probes, "ActImpl", "plain"))
        assertEquals(annotated("io.temporal.activity.ActivityInterface"), outsideCallerOf(probes, "ActChildImpl", "grand"))
        assertNull(outsideCallerOf(probes, "ActLooseImpl", "loose"))
        assertEquals(annotated("io.temporal.activity.ActivityInterface"), outsideCallerOf(probes, "ActOutsideImpl", "outside"))
    }

    @Test
    fun `an Axon annotation labels its method and an inherited Temporal one yields to it`() {
        val probes = manifest("AxonImpl", "AxonApiImpl", "AxonStartChild", "AxonOverTemporal", "Axon5Child")

        assertEquals(annotated("org.axonframework.commandhandling.CommandHandler"), outsideCallerOf(probes, "AxonImpl", "command"))
        assertEquals(annotated("org.axonframework.eventhandling.EventHandler"), outsideCallerOf(probes, "AxonApiImpl", "event"))
        assertNull(outsideCallerOf(probes, "AxonApiImpl", "start"))
        assertNull(outsideCallerOf(probes, "AxonStartChild", "start"))
        assertEquals(annotated("org.axonframework.commandhandling.CommandHandler"), outsideCallerOf(probes, "AxonOverTemporal", "poke"))
        assertEquals(
            annotated("org.axonframework.messaging.commandhandling.annotation.CommandHandler"),
            outsideCallerOf(probes, "Axon5Child", "command"),
        )
    }
}
