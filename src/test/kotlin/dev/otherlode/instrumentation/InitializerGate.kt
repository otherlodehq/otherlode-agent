package dev.otherlode.instrumentation

import java.util.concurrent.CountDownLatch

object InitializerGate {
    @JvmField
    var entered: CountDownLatch = CountDownLatch(1)

    @JvmField
    var release: CountDownLatch = CountDownLatch(1)

    fun reset() {
        entered = CountDownLatch(1)
        release = CountDownLatch(1)
    }
}
