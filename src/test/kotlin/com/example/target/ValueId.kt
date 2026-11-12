package com.example.target.outsidecaller

@JvmInline
value class ValueId(
    val v: Int,
) : Comparable<ValueId> {
    override fun compareTo(other: ValueId): Int = v - other.v
}
