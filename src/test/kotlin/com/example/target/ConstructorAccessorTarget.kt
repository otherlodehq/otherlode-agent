package com.example.target

class AccessorPair private constructor(
    val a: Int,
    val b: Int,
) {
    constructor(a: Int) : this(a, 0)

    companion object {
        fun make(): AccessorPair = AccessorPair(1, 2)
    }
}

class AccessorName private constructor(
    val name: String,
) {
    companion object {
        fun of(name: String): AccessorName = AccessorName(name)
    }
}

class AccessorDefaults private constructor(
    val name: String,
    val size: Int = 1,
) {
    companion object {
        fun full(): AccessorDefaults = AccessorDefaults("a", 2)

        fun omitting(): AccessorDefaults = AccessorDefaults("a")
    }
}

sealed class AccessorShape {
    class Circle : AccessorShape()
}

class SelfDefault(
    val v: Int,
    val next: SelfDefault? = SelfDefault(0, null, 0),
) {
    constructor(v: Int, next: SelfDefault?, extra: Int) : this(v, next)
}

object SelfDefaultUser {
    fun make(): SelfDefault = SelfDefault(1)
}
