package com.example.target.keypairs

class TwoLocalsSameType {
    fun check(
        x: Int,
        y: Int,
    ): String {
        val first = if (x > 0) "pos" else "non-pos"
        val second = if (y > 0) "pos" else "non-pos"
        return first + second
    }
}
