package dev.otherlode.instrumentation

import kotlin.test.Test
import kotlin.test.assertTrue

class VerifierHookJfrBeforeTest : VerifierHookJfrScenario() {
    @Test
    fun `the hook still reads the message and JFR still records it when JFR is started before the hook installs`() {
        val recording = startJfr()
        assertTrue(hook.install(instrumentation))
        try {
            assertBothSee("com/example/v/JfrBefore", recording)
        } finally {
            hook.uninstall(instrumentation)
        }
    }
}
