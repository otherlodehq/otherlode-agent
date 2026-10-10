package dev.otherlode.instrumentation

import dev.otherlode.config.AgentConfig
import dev.otherlode.export.OutsideCaller
import dev.otherlode.export.OutsideCallerKind
import dev.otherlode.export.ProbeKind
import dev.otherlode.export.ProbeLocation
import dev.otherlode.export.ResourceAttributes
import dev.otherlode.instrumentation.branch.ConfiguredCallbackAnnotations
import dev.otherlode.registry.ProbeRegistry
import net.bytebuddy.agent.ByteBuddyAgent
import net.bytebuddy.agent.builder.ResettableClassFileTransformer
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Proves that an annotation the adopter names with `callbackAnnotations` gives a method a
 * callback-annotation [OutsideCaller] through the real transform pipeline, at either retention.
 */
class NamedCallbackAnnotationInstrumentationTest {
    private companion object {
        const val PACKAGE = "com.example.target.named"
        const val NAMED = "$PACKAGE.NamedCallbacks"
        const val ON_THE_CLASS = "$PACKAGE.OnTheClass"
        const val OUTSIDE = "com.example.outside.named"
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

    /**
     * Weaves the fixtures with [names] as the `callbackAnnotations` option, or no option when null, and
     * returns the manifest. [holder] replaces the one built from the option when given. A transformer
     * an earlier call in the same test installed is removed first.
     */
    private fun manifest(
        names: String?,
        holder: ConfiguredCallbackAnnotations? = null,
    ): List<ProbeLocation> {
        tearDown()
        val registry = ProbeRegistry()
        val option = if (names == null) "" else ",callbackAnnotations=$names"
        val config = AgentConfig.parse("includePackages=$PACKAGE$option")
        val otherlode =
            OtherlodeInstrumentation(
                config,
                registry,
                callbackAnnotations = holder ?: ConfiguredCallbackAnnotations(config.callbackAnnotations),
            )
        installed = otherlode to otherlode.install(instrumentation)
        val loader =
            FixtureClassLoader(
                arrayOf(File("build/classes/java/test").toURI().toURL(), File("build/classes/kotlin/test").toURI().toURL()),
                javaClass.classLoader,
            )
        Class.forName(NAMED, true, loader)
        Class.forName(ON_THE_CLASS, true, loader)
        return registry.manifest(RESOURCE).probes.filter { it.kind == ProbeKind.METHOD }
    }

    private fun outsideCallerOf(
        probes: List<ProbeLocation>,
        method: String,
        className: String = NAMED,
    ): OutsideCaller? = probes.single { it.className == className && it.methodName == method }.outsideCaller

    @Test
    fun `a method carrying a named runtime annotation from outside the scope gets it as its outside caller`() {
        val probes = manifest("$OUTSIDE.RuntimeCall")

        assertEquals(annotated("$OUTSIDE.RuntimeCall"), outsideCallerOf(probes, "runtimeCall"))
    }

    @Test
    fun `a named annotation inside the scope marks the method`() {
        val probes = manifest("$PACKAGE.InScopeCall")

        assertEquals(annotated("$PACKAGE.InScopeCall"), outsideCallerOf(probes, "inScope"))
    }

    @Test
    fun `with the option unset the same methods get no outside caller`() {
        val probes = manifest(null)

        assertNull(outsideCallerOf(probes, "runtimeCall"))
        assertNull(outsideCallerOf(probes, "classCall"))
        assertNull(outsideCallerOf(probes, "composedOfRuntime"))
        assertNull(outsideCallerOf(probes, "composedOfClass"))
    }

    @Test
    fun `a named annotation with class retention marks the method, and naming a different one does not`() {
        assertEquals(annotated("$OUTSIDE.ClassCall"), outsideCallerOf(manifest("$OUTSIDE.ClassCall"), "classCall"))
        assertNull(outsideCallerOf(manifest("$OUTSIDE.RuntimeCall"), "classCall"))
    }

    @Test
    fun `a nested annotation is matched when named with a dot`() {
        val probes = manifest("$OUTSIDE.Bus.Handler")

        assertEquals(annotated("$OUTSIDE.Bus\$Handler"), outsideCallerOf(probes, "nested"))
    }

    @Test
    fun `a nested annotation is matched when named with a dollar sign`() {
        val probes = manifest("$OUTSIDE.Bus\$Handler")

        assertEquals(annotated("$OUTSIDE.Bus\$Handler"), outsideCallerOf(probes, "nested"))
    }

    @Test
    fun `a composed annotation carrying a named runtime annotation marks the method and is the one named`() {
        val probes = manifest("$OUTSIDE.RuntimeCall")

        assertEquals(annotated("$PACKAGE.ComposedOfRuntime"), outsideCallerOf(probes, "composedOfRuntime"))
    }

    @Test
    fun `a composed annotation carrying a named class-retention annotation marks the method and is the one named`() {
        val probes = manifest("$OUTSIDE.ClassCall")

        assertEquals(annotated("$PACKAGE.ComposedOfClass"), outsideCallerOf(probes, "composedOfClass"))
    }

    @Test
    fun `a named annotation on a parameter marks nothing`() {
        val probes = manifest("$OUTSIDE.RuntimeCall")

        assertNull(outsideCallerOf(probes, "onParameter"))
    }

    @Test
    fun `a named annotation on the class marks none of its methods`() {
        val probes = manifest("$OUTSIDE.RuntimeCall")

        assertNull(outsideCallerOf(probes, "method", ON_THE_CLASS))
    }

    @Test
    fun `an annotation that is not named marks nothing, whatever it sits beside`() {
        val probes = manifest("$OUTSIDE.RuntimeCall;$OUTSIDE.ClassCall")

        assertNull(outsideCallerOf(probes, "otherCall"))
        assertNull(outsideCallerOf(probes, "plain"))
    }

    @Test
    fun `a built-in callback annotation still marks the method with the option set`() {
        val probes = manifest("$OUTSIDE.RuntimeCall")

        assertEquals(annotated("org.springframework.context.event.EventListener"), outsideCallerOf(probes, "builtIn"))
    }

    @Test
    fun `a named annotation used twice is matched through its container, which is the one named`() {
        val probes = manifest("$OUTSIDE.RepeatedCall")

        assertEquals(annotated("$OUTSIDE.RepeatedCalls"), outsideCallerOf(probes, "repeated"))
    }

    @Test
    fun `a named class-retention annotation used twice is matched through its class-retention container`() {
        val probes = manifest("$OUTSIDE.ClassRepeated")

        assertEquals(annotated("$OUTSIDE.ClassRepeatedList"), outsideCallerOf(probes, "classRepeated"))
    }

    @Test
    fun `a container is matched only for a named annotation`() {
        assertNull(outsideCallerOf(manifest("$OUTSIDE.RuntimeCall"), "repeated"))
        assertNull(outsideCallerOf(manifest(null), "repeated"))
    }

    @Test
    fun `a class-retention annotation carrying a built-in one marks nothing, with the option set or not`() {
        assertNull(outsideCallerOf(manifest("$OUTSIDE.RuntimeCall"), "classComposedOfBuiltIn"))
        assertNull(outsideCallerOf(manifest(null), "classComposedOfBuiltIn"))
    }

    @Test
    fun `a class-retention annotation carrying a named one marks the method and is the one named`() {
        val probes = manifest("$OUTSIDE.RuntimeCall")

        assertEquals(annotated("$PACKAGE.ClassComposedOfNamed"), outsideCallerOf(probes, "classComposedOfNamed"))
    }

    @Test
    fun `weaving records every named annotation found, so only a name on no method stays unseen`() {
        val config =
            AgentConfig.parse(
                "includePackages=$PACKAGE,callbackAnnotations=$OUTSIDE.RuntimeCall;$OUTSIDE.ClassCall;$OUTSIDE.Bus.Handler;" +
                    "$OUTSIDE.RuntimeCal",
            )
        val holder = ConfiguredCallbackAnnotations(config.callbackAnnotations)

        manifest(null, holder)

        assertEquals(listOf("$OUTSIDE.RuntimeCal"), holder.unseen().map { it.written })
    }
}
