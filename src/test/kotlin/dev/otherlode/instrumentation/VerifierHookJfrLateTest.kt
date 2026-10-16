package dev.otherlode.instrumentation

import kotlin.test.Test
import kotlin.test.assertTrue

class VerifierHookJfrLateTest : VerifierHookJfrScenario() {
    @Test
    fun `the hook still reads the message and JFR still records it when JFR is started after the hook has already read a rejection`() {
        assertTrue(hook.install(instrumentation))
        try {
            VerifierHookFixtures.link(
                "com/example/v/JfrEarly",
                VerifierHookFixtures.rejectedClass("com/example/v/JfrEarly", VerifierHookFixtures.V17),
            )
            val recording = startJfr()
            assertBothSee("com/example/v/JfrLate", recording)
        } finally {
            hook.uninstall(instrumentation)
        }
    }
}
