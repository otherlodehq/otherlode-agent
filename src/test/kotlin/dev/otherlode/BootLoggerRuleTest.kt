package dev.otherlode

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BootLoggerRuleTest {
    private val allowed = emptySet<String>()

    @Test
    fun `no main source but OtherlodeLoggers calls System getLogger`() {
        val root = File(System.getProperty("user.dir"))
        val sourceRoots =
            listOf(File(root, "src/main")) +
                root
                    .listFiles { f -> f.isDirectory }
                    .orEmpty()
                    .map { File(it, "src/main") }
        val scanned =
            sourceRoots
                .filter { it.isDirectory }
                .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java") }.toList() }
        assertTrue(scanned.size > 100, "the scan found the agent's sources, found ${scanned.size}")
        val offenders =
            scanned
                .filter { it.relativeTo(root).invariantSeparatorsPath !in allowed }
                .filter { it.name != "OtherlodeLoggers.java" }
                .filter { "System.getLogger" in it.readText() }
                .map { it.relativeTo(root).invariantSeparatorsPath }
        assertEquals(emptyList(), offenders, "use OtherlodeLoggers.get, or bootLogger in the root module")
    }
}
