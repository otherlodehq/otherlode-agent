package com.example.target

// Both defaults must stay on the function's own line.
@Suppress("ktlint:standard:function-signature")
fun sameLine(a: Int = 1, b: Int = 2): Int = a + b

fun wrappedDefault(
    a: Int,
    label: String =
        "wrapped",
): String = label + a
