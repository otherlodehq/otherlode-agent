package com.example.target.outsidecaller

/**
 * A value class implementing a generic JDK interface. kotlinc's `compareTo(Object)I` bridge calls
 * `unbox-impl` and then the mangled `compareTo-...(I)I`, both on this class.
 */
@JvmInline
value class ValueId(
    val v: Int,
) : Comparable<ValueId> {
    override fun compareTo(other: ValueId): Int = v - other.v
}
