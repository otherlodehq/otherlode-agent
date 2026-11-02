package dev.soaktest.shop

import jakarta.annotation.PreDestroy
import org.springframework.stereotype.Component

@Component
class ShutdownHook {
    @PreDestroy
    fun close() {
        Thread.sleep(2000)
        ShutdownAudit.record()
    }
}
