package com.example.target

data class GeneratedPoint(
    val x: Int,
    val y: String,
) {
    fun custom(): Int = if (x > 0) x else -x
}

data class GeneratedPointCustomToString(
    val x: Int,
    val y: String,
) {
    override fun toString(): String = "($x, $y)"
}

data class GeneratedPointCustomEquals(
    val x: Int,
    val y: String,
) {
    override fun equals(other: Any?): Boolean {
        if (other !is GeneratedPointCustomEquals) return false
        return x == other.x
    }

    override fun hashCode(): Int = x
}

data class GeneratedPointWithDefault(
    val x: Int,
    val y: Int = 0,
)

class HandWrittenCopy(
    val v: Int,
) {
    fun copy(v: Int = this.v): HandWrittenCopy = HandWrittenCopy(v)

    fun component1(): Int = v
}

enum class GeneratedColour { RED, GREEN }

interface GeneratedInterface {
    fun withBody(): Int = 42

    val label: String
        get() = "enabled"
}

class GeneratedInterfaceImpl : GeneratedInterface

class NotDefaultImpls {
    fun withBody(): Int = 7
}

class Price
    @JvmOverloads
    constructor(
        val amount: Int,
        val currency: String = "GBP",
        val rounding: Int = 2,
    ) {
        @JvmOverloads
        fun format(
            a: Int,
            b: String = "x",
        ): String = "$amount $currency $a $b $rounding"
    }

@JvmOverloads
fun formatPrice(
    amount: Long,
    currency: String = "GBP",
    wide: Double = 1.0,
): String = "$amount $currency $wide"

class HandWrittenPrice(
    val amount: Int,
    val currency: String = "GBP",
    val rounding: Int = 2,
) {
    constructor(a: Long) : this(a.toInt(), "GBP", 2)

    fun formatShort(a: Int): String = format(a)

    fun format(
        a: Int,
        b: String = "x",
    ): String = "$amount $currency $a $b $rounding"
}
