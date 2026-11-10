package com.example.target.kotlinc

/** A data class whose every generated member is kotlinc's. */
data class Point(
    val i: Int,
    val s: String,
    val n: String?,
    val l: Long,
    val d: Double,
    val b: Boolean,
    val f: Float,
)

/** A generic data class with a nullable and a collection component. */
data class Box<T>(
    val t: T,
    val list: List<T>,
    val nt: T?,
)

/** A data class that writes its own `equals`, `hashCode` and `toString`, keeping only `componentN` and `copy` generated. */
data class Named(
    val a: Int,
    val b: String,
) {
    override fun equals(other: Any?): Boolean = other is Named && other.a == a

    override fun hashCode(): Int = a

    override fun toString(): String = "Named"
}

/** A data object whose every generated member is kotlinc's. */
data object Idle

/** A plain object, which kotlinc gives no generated members. */
object PlainSingleton

/** A data object that writes its own `toString`. */
data object NamedSingleton {
    override fun toString(): String = "Named"
}

/** A data object nested in a sealed interface, the usual way singletons are written. */
sealed interface Phase {
    data object Start : Phase

    data class Running(
        val step: Int,
    ) : Phase
}

/** A class with a static `INSTANCE` of its own type whose `toString`, `hashCode` and `equals` are all hand-written. */
class WrittenSingleton private constructor() {
    override fun toString(): String = "WrittenSingleton"

    override fun hashCode(): Int = 7

    override fun equals(other: Any?): Boolean = other is WrittenSingleton

    companion object {
        @JvmField
        val INSTANCE = WrittenSingleton()
    }
}
