package dev.soaktest.shop

import java.util.concurrent.atomic.AtomicInteger

object ShutdownAudit {
    private val recorded = AtomicInteger()

    fun armed(): Boolean = true

    fun record(): Int = recorded.incrementAndGet()
}
