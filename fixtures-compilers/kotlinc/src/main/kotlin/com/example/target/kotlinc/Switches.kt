package com.example.target.kotlinc

class Switches {
    fun enumWithElse(color: Color): Int =
        when (color) {
            Color.RED -> 1
            Color.BLUE -> 3
            else -> 0
        }

    fun enumExhaustive(color: Color): Int =
        when (color) {
            Color.RED -> 1
            Color.GREEN -> 2
            Color.BLUE -> 3
        }

    fun stringWhen(status: String): Int =
        when (status) {
            "open" -> 1
            "closed", "done" -> 2
            "Aa" -> 3
            "BB" -> 4
            else -> 0
        }
}
