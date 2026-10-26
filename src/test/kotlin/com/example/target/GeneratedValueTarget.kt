package com.example.target

/** A value class with one member of its own, so its generated statics and forwarders sit beside adopter code. */
@JvmInline
value class GeneratedValue(
    val v: Int,
) {
    fun twice(): Int = v * 2
}

/** A plain class that writes the data-class method names itself. */
class HandWrittenParts(
    val a: Int,
    val b: String,
) {
    fun component1(): Int = a

    fun component2(): String = b

    fun copy(
        a: Int = this.a,
        b: String = this.b,
    ): HandWrittenParts = HandWrittenParts(a, b)

    override fun equals(other: Any?): Boolean = other is HandWrittenParts && other.a == a

    override fun hashCode(): Int = a

    override fun toString(): String = "HandWrittenParts"
}
