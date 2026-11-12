package com.example.target.keypairs

class DifferentConditionalAddedEarlierV1 {
    fun check(
        x: Int,
        y: Int,
    ): String = if (x > 0) "pos" else "non-pos"
}

class DifferentConditionalAddedEarlierV2 {
    fun check(
        x: Int,
        y: Int,
    ): String {
        if (y > 0) println("y is positive")
        return if (x > 0) "pos" else "non-pos"
    }
}
