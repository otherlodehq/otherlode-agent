package com.example.target.kotlinc

import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED

/**
 * Compares an adopter writes with the public suspended marker, held in a local, in a suspend
 * function with a suspension point: the adopter's conditionals, not coroutine machinery.
 */
suspend fun ownMarker(x: Any?): Int {
    val marker = COROUTINE_SUSPENDED
    if (x == marker) return 1
    if (x === marker) return 2
    pause()
    return 0
}

private suspend fun pause() {}

/** A switch an adopter writes on a string's hash, as code did before switches on strings existed. */
fun ownHashSwitch(s: String): Int {
    when (s.hashCode()) {
        97 -> if (s.equals("a")) return 1
        98 -> if (s.equals("b")) return 2
    }
    return 0
}

/**
 * A string `when` whose `null` shares a branch with a literal. kotlinc tests the subject for null
 * before hashing it with no `dup`, a lowering the agent does not read, so the collision side of
 * each bucket's last check is reported as an unread shape.
 */
fun nullSharingWhen(s: String?): Int =
    when (s) {
        "auto", null -> 1
        "on", "" -> 2
        "off" -> 3
        else -> 4
    }
