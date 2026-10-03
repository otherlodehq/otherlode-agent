package com.example.target.kotlinc

fun defaults(
    a: Int,
    b: String = "x",
    c: Long = 2L,
    d: Any? = null,
): String = "$a$b$c$d"

class Member {
    fun memberDefaults(
        a: Int,
        b: Int = 5,
    ): Int = a + b
}

class Overloaded
    @JvmOverloads
    constructor(
        val a: Int,
        val b: String = "b",
        val c: Long = 3L,
    ) {
        @JvmOverloads
        fun mem(
            a: Int,
            b: String = "x",
        ): String = "$a$b"
    }

@JvmOverloads
fun overloads(
    a: Int,
    b: String = "x",
    c: Int = 2,
): String = "$a$b$c"
