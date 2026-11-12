package com.example.target.keypairs

inline fun isPositive(x: Int): Boolean = x > 0

class InlineKeyCollision {
    fun calledTwiceInOneMethod(
        a: Int,
        b: Int,
    ): Int {
        var total = 0
        if (isPositive(a)) total++
        if (isPositive(b)) total++
        return total
    }

    fun calledOnceInMethodA(a: Int): Int = if (isPositive(a)) 1 else 0

    fun calledOnceInMethodB(b: Int): Int = if (isPositive(b)) 1 else 0
}
