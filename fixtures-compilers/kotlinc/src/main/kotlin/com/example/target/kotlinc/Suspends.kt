package com.example.target.kotlinc

import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** Suspends through `suspendCoroutine`, whose inlined body holds the stack-form suspended-marker compare. */
suspend fun leaf(): Int = suspendCoroutine { it.resume(1) }

suspend fun topLevel(x: Int): Int {
    val a = leaf()
    return if (x > a) 1 else 2 // marker: topLevel-if
}

class Holder {
    suspend fun member(x: Int): Int {
        val a = leaf()
        val b = leaf()
        return if (a > x) a else b // marker: member-if
    }
}

val lam: suspend (Int) -> Int = { x ->
    val a = leaf()
    if (a > x) 1 else 0 // marker: lambda-if
}
