package dev.otherlode.instrumentation

import java.util.concurrent.CountDownLatch

/** Two latches a fixture's static initialiser reaches, so a test can hold an initialiser mid-body. */
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
