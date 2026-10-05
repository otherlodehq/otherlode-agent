package com.example.target

/** The callee two classes share, so their probes name the same class, method and descriptor. */
class CompactionShared {
    fun shared(): Int = 1
}

/** Calls [CompactionShared.shared] from a method named `run`. */
class CompactionFirst {
    fun run(): Int = CompactionShared().shared()

    fun leaf(): Int = 3
}

/** Calls [CompactionShared.shared] from a method also named `run`, with the same descriptor. */
class CompactionSecond {
    fun run(): Int = CompactionShared().shared()

    fun leaf(): Int = 4
}
