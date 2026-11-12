package com.example.target.kotlinc

data class Point(
    val i: Int,
    val s: String,
    val n: String?,
    val l: Long,
    val d: Double,
    val b: Boolean,
    val f: Float,
)

data class Box<T>(
    val t: T,
    val list: List<T>,
    val nt: T?,
)

data class Named(
    val a: Int,
    val b: String,
) {
    override fun equals(other: Any?): Boolean = other is Named && other.a == a

    override fun hashCode(): Int = a

    override fun toString(): String = "Named"
}

data object Idle

object PlainSingleton

data object NamedSingleton {
    override fun toString(): String = "Named"
}

sealed interface Phase {
    data object Start : Phase

    data class Running(
        val step: Int,
    ) : Phase
}

class WrittenSingleton private constructor() {
    override fun toString(): String = "WrittenSingleton"

    override fun hashCode(): Int = 7

    override fun equals(other: Any?): Boolean = other is WrittenSingleton

    companion object {
        @JvmField
        val INSTANCE = WrittenSingleton()
    }
}
