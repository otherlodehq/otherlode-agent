@file:JvmName("Utils")
@file:JvmMultifileClass

package com.example.target.kotlinc

fun util1(a: Int): Int = a + 1

fun util1d(
    a: Int,
    b: Int = 2,
): Int = a + b
