package dev.otherlode.instrumentation

import dev.otherlode.bootstrap.OtherlodeShutdownHooks
import java.lang.instrument.Instrumentation
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The weave of `runHooks`, on a stand-in class with the shape of the JDK's. */
class ShutdownHooksHookTest {
    private val instrumentation: Instrumentation = VerifierHookFixtures.instrumentation()
    private val standIn = StandInShutdownHooks::class.java.name

    @BeforeTest
    @AfterTest
    fun forgetTable() {
        OtherlodeShutdownHooks.forget()
        StandInShutdownHooks.reset()
    }

    @Test
    fun `running the hooks hands the table to the seam, with the keys the class held`() {
        val hook = ShutdownHooksHook(targetClass = standIn, loader = StandInShutdownHooks::class.java.classLoader)
        assertTrue(hook.install(instrumentation))
        try {
            val first = Thread {}
            val second = Thread {}
            StandInShutdownHooks.add(first)
            StandInShutdownHooks.add(second)
            assertNull(OtherlodeShutdownHooks.hooks(), "nothing is offered before the hooks run")

            StandInShutdownHooks.runHooks()

            assertTrue(StandInShutdownHooks.tableCleared(), "the class's own method still ran")
            val seen = OtherlodeShutdownHooks.hooks()!!.toSet()
            assertEquals(setOf(first, second), seen)
        } finally {
            hook.uninstall(instrumentation)
        }
    }

    @Test
    fun `after uninstall running the hooks hands nothing to the seam`() {
        val hook = ShutdownHooksHook(targetClass = standIn, loader = StandInShutdownHooks::class.java.classLoader)
        assertTrue(hook.install(instrumentation))
        hook.uninstall(instrumentation)

        StandInShutdownHooks.runHooks()

        assertNull(OtherlodeShutdownHooks.hooks())
    }

    @Test
    fun `the weave opens nothing in java base`() {
        val hook = ShutdownHooksHook(targetClass = standIn, loader = StandInShutdownHooks::class.java.classLoader)
        assertTrue(hook.install(instrumentation))
        try {
            val javaBase = Any::class.java.module
            val agentModule = ShutdownHooksHook::class.java.module
            val seamModule = OtherlodeShutdownHooks::class.java.module
            assertFalse(javaBase.isOpen("java.lang", agentModule), "java.lang is not open to the agent's module")
            assertFalse(javaBase.isOpen("java.lang", seamModule), "java.lang is not open to the seam's module")
            assertFalse(javaBase.isOpen("java.lang"), "java.lang is open to nobody")
            assertTrue(javaBase.canRead(seamModule), "java.base reads the seam")
        } finally {
            hook.uninstall(instrumentation)
        }
    }

    @Test
    fun `a class without the hooks field gets no weave, one warning and a false`() {
        val hook =
            ShutdownHooksHook(
                targetClass = StandInWithoutHooksField::class.java.name,
                loader = StandInWithoutHooksField::class.java.classLoader,
            )

        val records = captureLogRecords(ShutdownHooksHook::class.java.name) { assertFalse(hook.install(instrumentation)) }

        val warnings = records.filter { it.level == Level.WARNING }
        assertEquals(1, warnings.size, "one warning: $records")
        assertTrue("shutdown hooks" in warnings.single().message, warnings.single().message)
        StandInWithoutHooksField.runHooks()
        assertNull(OtherlodeShutdownHooks.hooks())
    }

    @Test
    fun `a class that does not exist gets one warning and a false`() {
        val hook = ShutdownHooksHook(targetClass = "no.such.Hooks")

        val records = captureLogRecords(ShutdownHooksHook::class.java.name) { assertFalse(hook.install(instrumentation)) }

        assertEquals(1, records.count { it.level == Level.WARNING }, "$records")
    }

    private fun captureLogRecords(
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
        val logger = Logger.getLogger(loggerName)
        val original = logger.level
        logger.addHandler(handler)
        logger.level = Level.ALL
        try {
            block()
        } finally {
            logger.removeHandler(handler)
            logger.level = original
        }
        return records
    }
}
