package dev.otherlode.benchmark

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** The macro run. Only included when the build is given -PagentJar and -PcollectorDir. */
@Tag("macro")
class OverheadBenchmark {
    @Test
    fun `measure petclinic with and without the agent`() {
        Harness(BenchSettings.fromSystemProperties()).runAll()
    }
}
