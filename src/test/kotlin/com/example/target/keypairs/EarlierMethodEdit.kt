package com.example.target.keypairs

class EarlierMethodEditV1 {
    fun first(x: Int): Int = x + 1

    fun second(x: Int): String = if (x > 0) "pos" else "non-pos"
}

class EarlierMethodEditV2 {
    fun first(x: Int): Int = if (x > 0) x + 1 else x - 1

    fun second(x: Int): String = if (x > 0) "pos" else "non-pos"
}
