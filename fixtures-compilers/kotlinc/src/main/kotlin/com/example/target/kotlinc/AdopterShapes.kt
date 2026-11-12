package com.example.target.kotlinc

import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED

suspend fun ownMarker(x: Any?): Int {
    val marker = COROUTINE_SUSPENDED
    if (x == marker) return 1
    if (x === marker) return 2
    pause()
    return 0
}

private suspend fun pause() {}

fun ownHashSwitch(s: String): Int {
    when (s.hashCode()) {
        97 -> if (s.equals("a")) return 1
        98 -> if (s.equals("b")) return 2
    }
    return 0
}

fun nullSharingWhen(s: String?): Int =
    when (s) {
        "auto", null -> 1
        "on", "" -> 2
        "off" -> 3
        else -> 4
    }
