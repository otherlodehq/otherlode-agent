package dev.otherlode.instrumentation

import dev.otherlode.bootstrap.OtherlodeVerifierMessages
import dev.otherlode.config.AgentConfig
import dev.otherlode.export.InitializationFailure
import dev.otherlode.export.ProbeKind
import dev.otherlode.export.TestResources
import dev.otherlode.registry.EndpointRegistry
import dev.otherlode.registry.ProbeMeta
import dev.otherlode.registry.ProbeRegistry
import net.bytebuddy.agent.builder.AgentBuilder
import net.bytebuddy.asm.AsmVisitorWrapper
import net.bytebuddy.description.field.FieldDescription
import net.bytebuddy.description.field.FieldList
import net.bytebuddy.description.method.MethodList
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.DynamicType
import net.bytebuddy.implementation.Implementation
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.ClassWriter
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import net.bytebuddy.matcher.ElementMatchers.named
import net.bytebuddy.matcher.ElementMatchers.none
import net.bytebuddy.pool.TypePool
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.lang.instrument.Instrumentation
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import java.util.logging.Level as JulLevel

/**
 * The hook on `java.lang.VerifyError` against the real JVM (ADR 0071). Runs in a JVM of its own,
 * `verifierHookTest`, because it weaves a boot class for the rest of the process.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VerifierHookTest {
    private val instrumentation: Instrumentation = VerifierHookFixtures.instrumentation()
    private val own = OwnRetransformationFailures()
    private val hook = VerifyErrorHook(own)
    private val fixtureNames = AtomicInteger()

    @BeforeAll
    fun installHook() {
        assertTrue(hook.install(instrumentation), "the hook must install")
        assertTrue(hook.watch().watched)
    }

    @AfterAll
    fun uninstallHook() {
        hook.uninstall(instrumentation)
    }

    /**
     * Rejected classes named afresh on each call: the ring keeps a message equal to one it kept
     * before only once, and the tests of this class share one JVM.
     */
    private fun rejectedByVerifier(): List<Triple<String, Int, String>> {
        val n = fixtureNames.incrementAndGet()
        return listOf(
            Triple("com/example/v/SplitV52n$n", VerifierHookFixtures.V1_8, "com.example.v.SplitV52n$n"),
            Triple("com/example/v/SplitV61n$n", VerifierHookFixtures.V17, "com.example.v.SplitV61n$n"),
            Triple("com/example/v/OldV49n$n", VerifierHookFixtures.V1_5, "com.example.v.OldV49n$n"),
        )
    }

    private fun rejectedNames(registry: ProbeRegistry): List<String> =
        registry
            .manifest(TestResources.forConfig(AgentConfig.parse("serviceName=x")))
            .classesFailedToInitialize
            .filter { it.reason == InitializationFailure.VERIFIER_REJECTED }
            .map { it.className }

    @Test
    fun `a class the verifier rejects reaches the registry and goes out on one manifest, once, in each format`() {
        val registry = ProbeRegistry()
        val exporter = VerifierHookFixtures.RecordingExporter()
        val scheduler = VerifierHookFixtures.scheduler(registry, exporter, hook.watch())
        val rejected = rejectedByVerifier()
        for ((internalName, version, _) in rejected) {
            VerifierHookFixtures.link(internalName, VerifierHookFixtures.rejectedClass(internalName, version))
        }

        scheduler.flush()
        scheduler.flush()

        val sent = exporter.manifests.flatMap { it.classesFailedToInitialize }
        for ((_, _, name) in rejected) {
            assertEquals(1, sent.count { it.className == name && it.reason == InitializationFailure.VERIFIER_REJECTED }, name)
        }
        assertEquals(0L, exporter.manifests.last().unplacedVerificationFailures)
    }

    @Test
    fun `every kind of manifest says the run watched the verifier`() {
        val classes = ProbeRegistry().apply { register("com.example.A", 1L, listOf(ProbeMeta(ProbeKind.METHOD, "a", "()V", 1))) }
        val classExporter = VerifierHookFixtures.RecordingExporter()
        VerifierHookFixtures.scheduler(classes, classExporter, hook.watch()).flush()

        val endpoints = EndpointRegistry()
        endpoints.register(key = Any(), framework = "http-server", verb = "GET", verbatimTemplate = "/health")
        val riderExporter = VerifierHookFixtures.RecordingExporter()
        VerifierHookFixtures.scheduler(ProbeRegistry(), riderExporter, hook.watch(), endpoints).flush()

        val emptyExporter = VerifierHookFixtures.RecordingExporter()
        VerifierHookFixtures.scheduler(ProbeRegistry(), emptyExporter, hook.watch()).flush()

        assertTrue(classExporter.manifests.any { it.probes.isNotEmpty() })
        assertTrue(riderExporter.manifests.any { it.probes.isEmpty() && it.endpoints.isNotEmpty() })
        assertTrue(emptyExporter.manifests.any { it.probes.isEmpty() && it.dependenciesListed })
        for (manifest in classExporter.manifests + riderExporter.manifests + emptyExporter.manifests) {
            assertTrue(manifest.verifierRejectionsWatched, "$manifest")
        }
    }

    @Test
    fun `a failed retransform of a running class by the agent's own path is dropped, and the same failure unrecorded is not`() {
        val recorded = loadVictim("com/example/v/RecordedVictim")
        val unrecorded = loadVictim("com/example/v/UnrecordedVictim")
        VerifierHookFixtures.scheduler(ProbeRegistry(), VerifierHookFixtures.RecordingExporter(), hook.watch()).flush()

        failRetransform(recorded, RetransformationOutcome(recorded.name, own))
        failRetransform(unrecorded, RetransformationOutcome(unrecorded.name, OwnRetransformationFailures()))

        val registry = ProbeRegistry()
        hook.watch().drainInto(registry)
        val names = rejectedNames(registry)
        assertFalse(recorded.name in names, "the agent's own failed retransform is dropped: $names")
        assertTrue(unrecorded.name in names, "another failure of the same shape is read: $names")
    }

    private fun loadVictim(internalName: String): Class<*> {
        val bytes = victimBytes(internalName)
        val name = internalName.replace('/', '.')
        val loader =
            object : ClassLoader(VerifierHookTest::class.java.classLoader) {
                override fun findClass(name: String): Class<*> = defineClass(name, bytes, 0, bytes.size)
            }
        return Class.forName(name, true, loader)
    }

    private fun victimBytes(internalName: String): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        writer.visit(VerifierHookFixtures.V17, Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER, internalName, null, "java/lang/Object", null)
        val method = writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "m", "()Ljava/lang/Object;", null, null)
        method.visitCode()
        method.visitLdcInsn("fine")
        method.visitInsn(Opcodes.ARETURN)
        method.visitMaxs(0, 0)
        method.visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun failRetransform(
        victim: Class<*>,
        outcome: RetransformationOutcome,
    ) {
        val breakBody =
            object : AsmVisitorWrapper.AbstractBase() {
                override fun mergeWriter(flags: Int) = flags

                override fun mergeReader(flags: Int) = flags

                override fun wrap(
                    instrumentedType: TypeDescription,
                    classVisitor: ClassVisitor,
                    implementationContext: Implementation.Context,
                    typePool: TypePool,
                    fields: FieldList<FieldDescription.InDefinedShape>,
                    methods: MethodList<*>,
                    writerFlags: Int,
                    readerFlags: Int,
                ): ClassVisitor =
                    object : ClassVisitor(Opcodes.ASM9, classVisitor) {
                        override fun visitMethod(
                            access: Int,
                            name: String,
                            descriptor: String,
                            signature: String?,
                            exceptions: Array<out String>?,
                        ): MethodVisitor? {
                            val target = super.visitMethod(access, name, descriptor, signature, exceptions)
                            if (name != "m" || target == null) return target
                            return object : MethodVisitor(Opcodes.ASM9, null) {
                                override fun visitEnd() {
                                    target.visitCode()
                                    target.visitInsn(Opcodes.ICONST_0)
                                    target.visitInsn(Opcodes.ARETURN)
                                    target.visitMaxs(1, 0)
                                    target.visitEnd()
                                }
                            }
                        }
                    }
            }
        val transformer =
            AgentBuilder
                .Default()
                .with(AgentBuilder.TypeStrategy.Default.DECORATE)
                .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
                .disableClassFormatChanges()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(AgentBuilder.RedefinitionStrategy.DiscoveryStrategy.Explicit(victim))
                .with(outcome as AgentBuilder.RedefinitionStrategy.Listener)
                .with(outcome as AgentBuilder.Listener)
                .ignore(none())
                .type(named(victim.name))
                .transform { builder: DynamicType.Builder<*>, _, _, _, _ -> builder.visit(breakBody) }
                .installOn(instrumentation)
        try {
            assertNotNull(outcome.failure(), "the retransformation of ${victim.name} must fail")
        } finally {
            instrumentation.removeTransformer(transformer)
        }
    }

    @Test
    fun `a hook that cannot install leaves the run unwatched and logs one warning naming why`() {
        val failing = VerifyErrorHook(OwnRetransformationFailures(), targetClass = "java.lang.NoSuchVerifyError")
        var installed = true

        val records = VerifierHookFixtures.captureLog(VerifyErrorHook::class.java.name) { installed = failing.install(instrumentation) }

        assertFalse(installed)
        assertFalse(failing.watch().watched)
        val warning = records.single()
        assertEquals(JulLevel.WARNING, warning.level)
        assertTrue("NoSuchVerifyError" in warning.message, warning.message)

        val exporter = VerifierHookFixtures.RecordingExporter()
        VerifierHookFixtures.scheduler(ProbeRegistry(), exporter, failing.watch()).flush()
        assertTrue(exporter.manifests.isNotEmpty())
        assertTrue(exporter.manifests.none { it.verifierRejectionsWatched })
    }

    @Test
    fun `a VerifyError built with a null message is ignored, not counted`() {
        val registry = ProbeRegistry()
        hook.watch().drainInto(registry)
        val before = registry.unplacedVerificationFailures()

        VerifyError()
        hook.watch().drainInto(registry)

        assertEquals(before, registry.unplacedVerificationFailures())
    }

    @Test
    fun `a message recorded while the hook installs is carried to the first drain, and the install's own is not`() {
        hook.uninstall(instrumentation)
        OtherlodeVerifierMessages.record("Bad type\nException Details:\n  Location:\n    com/example/Early.m()V @0: return\n")
        val fresh = VerifyErrorHook(own)
        try {
            assertTrue(fresh.install(instrumentation), "the hook must install")
            val registry = ProbeRegistry()
            fresh.watch().drainInto(registry)

            assertEquals(listOf("com.example.Early"), rejectedNames(registry))
            assertEquals(0L, registry.unplacedVerificationFailures())
        } finally {
            fresh.uninstall(instrumentation)
            assertTrue(hook.install(instrumentation), "the class's hook must install again")
        }
    }

    @Test
    fun `a VerifyError in no read format counts as unplaced`() {
        val registry = ProbeRegistry()
        hook.watch().drainInto(registry)

        VerifyError("Could not link verifier")
        hook.watch().drainInto(registry)

        assertEquals(1L, registry.unplacedVerificationFailures())
    }
}
