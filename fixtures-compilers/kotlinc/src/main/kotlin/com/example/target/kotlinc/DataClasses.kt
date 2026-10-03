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
