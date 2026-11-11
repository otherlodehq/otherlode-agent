package dev.otherlode.instrumentation

import dev.otherlode.config.AgentConfig
import dev.otherlode.export.TestResources
import dev.otherlode.registry.ProbeRegistry
import jdk.jfr.Recording
import jdk.jfr.consumer.RecordingFile
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertTrue

abstract class VerifierHookJfrScenario {
    protected val instrumentation = VerifierHookFixtures.instrumentation()
    protected val hook = VerifyErrorHook()

    protected fun startJfr(): Recording =
        Recording().apply {
            enable("jdk.JavaErrorThrow")
            start()
        }

    protected fun jfrMessages(recording: Recording): List<String> {
        recording.stop()
        val dump: Path = Files.createTempFile("verifier-hook", ".jfr")
        try {
            recording.dump(dump)
            return RecordingFile
                .readAllEvents(
                    dump,
                ).filter { it.eventType.name == "jdk.JavaErrorThrow" }
                .mapNotNull { it.getString("message") }
        } finally {
            recording.close()
            Files.deleteIfExists(dump)
        }
    }

    protected fun assertBothSee(
        internalName: String,
        recording: Recording,
    ) {
        VerifierHookFixtures.link(internalName, VerifierHookFixtures.rejectedClass(internalName, VerifierHookFixtures.V17))

        val registry = ProbeRegistry()
        hook.watch().drainInto(registry)
        val manifest =
            registry.manifest(TestResources.forConfig(AgentConfig.parse("serviceName=x")))
        val dotted = internalName.replace('/', '.')
        assertTrue(manifest.classesFailedToInitialize.any { it.className == dotted }, "the hook read $dotted")
        assertTrue(jfrMessages(recording).any { internalName in it }, "JFR recorded the VerifyError for $internalName")
    }
}
