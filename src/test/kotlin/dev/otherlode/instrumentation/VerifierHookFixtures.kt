package dev.otherlode.instrumentation

import dev.otherlode.config.AgentConfig
import dev.otherlode.export.ClassListing
import dev.otherlode.export.DeltaBatch
import dev.otherlode.export.ExportScheduler
import dev.otherlode.export.Exporter
import dev.otherlode.export.ProbeManifest
import dev.otherlode.export.StaticBaseline
import dev.otherlode.export.TestResources
import dev.otherlode.registry.DependencyRegistry
import dev.otherlode.registry.EndpointRegistry
import dev.otherlode.registry.ExternalClassRegistry
import dev.otherlode.registry.ProbeRegistry
import net.bytebuddy.agent.ByteBuddyAgent
import net.bytebuddy.jar.asm.ClassWriter
import net.bytebuddy.jar.asm.Opcodes
import java.lang.instrument.Instrumentation
import java.util.Collections
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Level as JulLevel
import java.util.logging.Logger as JulLogger

/**
 * Helpers for the tests that weave `java.lang.VerifyError`. They run in a JVM of their own
 * (`verifierHookTest`), with `-XX:+BytecodeVerificationLocal` so the woven boot class is itself
 * verified.
 */
internal object VerifierHookFixtures {
    const val V1_1 = Opcodes.V1_1
    const val V1_5 = Opcodes.V1_5
    const val V1_8 = Opcodes.V1_8
    const val V17 = Opcodes.V17

    fun instrumentation(): Instrumentation = ByteBuddyAgent.install().also { BootstrapHolder.install(it) }

    /** A class whose only method returns an int where an object is declared, which every verifier rejects. */
    fun rejectedClass(
        internalName: String,
        version: Int,
    ): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(version, Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER, internalName, null, "java/lang/Object", null)
        val method = writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "m", "()Ljava/lang/Object;", null, null)
        method.visitCode()
        method.visitInsn(Opcodes.ICONST_0)
        method.visitInsn(Opcodes.ARETURN)
        method.visitMaxs(1, 0)
        method.visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }

    private class Loader(
        private val name: String,
        private val bytes: ByteArray,
    ) : ClassLoader(VerifierHookFixtures::class.java.classLoader) {
        override fun findClass(name: String): Class<*> =
            if (name == this.name) defineClass(name, bytes, 0, bytes.size) else throw ClassNotFoundException(name)
    }

    /** Defines [internalName] in a fresh loader and links it; returns the `VerifyError` that refused it. */
    fun link(
        internalName: String,
        bytes: ByteArray,
    ): VerifyError {
        val name = internalName.replace('/', '.')
        try {
            Class.forName(name, true, Loader(name, bytes))
        } catch (e: VerifyError) {
            return e
        }
        throw AssertionError("$name linked, so the verifier did not reject it")
    }

    class RecordingExporter : Exporter {
        val manifests: MutableList<ProbeManifest> = Collections.synchronizedList(mutableListOf())

        override fun exportDeltaBatch(batch: DeltaBatch) {}

        override fun exportManifest(manifest: ProbeManifest) {
            manifests += manifest
        }

        override fun exportStaticBaseline(baseline: StaticBaseline) {}

        override fun exportClassListing(listing: ClassListing) {}
    }

    /** A scheduler over [registry] that treats the dependency listing as delivered. */
    fun scheduler(
        registry: ProbeRegistry,
        exporter: Exporter,
        watch: VerifierRejectionWatch,
        endpointRegistry: EndpointRegistry = EndpointRegistry(),
    ): ExportScheduler {
        val config = AgentConfig.parse("serviceName=checkout,serviceInstanceId=instance-1")
        val dependencies = DependencyRegistry().apply { markListingComplete() }
        return ExportScheduler(
            config,
            TestResources.forConfig(config),
            registry,
            endpointRegistry,
            exporter,
            dependencyRegistry = dependencies,
            externalClassRegistry = ExternalClassRegistry(dependencies::isListingComplete, { 0 }, dependencies::isSendable),
            verifierWatch = watch,
        )
    }

    fun captureLog(
        loggerName: String,
        block: () -> Unit,
    ): List<LogRecord> {
        val records = mutableListOf<LogRecord>()
        val handler =
            object : Handler() {
                override fun publish(record: LogRecord) {
                    records += record
                }

                override fun flush() {}

                override fun close() {}
            }
        val logger = JulLogger.getLogger(loggerName)
        val original = logger.level
        logger.addHandler(handler)
        logger.level = JulLevel.ALL
        try {
            block()
        } finally {
            logger.removeHandler(handler)
            logger.level = original
        }
        return records
    }
}
