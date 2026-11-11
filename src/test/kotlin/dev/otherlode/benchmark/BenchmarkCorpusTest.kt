package dev.otherlode.benchmark

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BenchmarkCorpusTest {
    @Test
    fun `every corpus the benchmark names holds classes`() {
        for (name in listOf("demo", "demo-spring", "scala", "spring-webmvc", "ktor-server-core")) {
            assertTrue(BenchmarkCorpus.load(name).classCount > 0, "corpus $name is empty")
        }
    }

    @Test
    fun `the analyser runs over every class of the demo corpus`() {
        val corpus = BenchmarkCorpus.load("demo")
        var analysed = 0
        corpus.analyzeAll { analysed++ }
        assertEquals(corpus.classCount, analysed)
    }
}
