package dev.otherlode.instrumentation

import kotlin.test.Test
import kotlin.test.assertTrue

class VerifierHookJfrAfterTest : VerifierHookJfrScenario() {
    @Test
    fun `the hook still reads the message and JFR still records it when JFR is started after the hook installs`() {
        assertTrue(hook.install(instrumentation))
        val recording = startJfr()
        try {
            assertBothSee("com/example/v/JfrAfter", recording)
        } finally {
            hook.uninstall(instrumentation)
        }
    }
}
