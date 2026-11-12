package com.example.testkittarget

class SampleTarget {
    fun exercised(): String = "used"

    fun neverCalled(): String = "dead code"

    fun neverCalledHelper(): String = neverCalledHelper2()

    fun neverCalledHelper2(): String = "dead code too"
}
