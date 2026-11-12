package com.example.target

class CompactionShared {
    fun shared(): Int = 1
}

class CompactionFirst {
    fun run(): Int = CompactionShared().shared()

    fun leaf(): Int = 3
}

class CompactionSecond {
    fun run(): Int = CompactionShared().shared()

    fun leaf(): Int = 4
}
