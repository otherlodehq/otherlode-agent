package com.example.target

import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.coroutines.resume
import kotlin.coroutines.startCoroutine
import kotlin.coroutines.suspendCoroutine

suspend fun pauseNow(): Int = suspendCoroutineUninterceptedOrReturn { 1 }

private var savedContinuation: Continuation<Int>? = null

suspend fun pauseLater(): Int =
    suspendCoroutineUninterceptedOrReturn { continuation ->
        savedContinuation = continuation
        COROUTINE_SUSPENDED
    }

fun resumePauseLater(value: Int) {
    val continuation = savedContinuation
    savedContinuation = null
    continuation?.resume(value)
}

suspend fun handOff(x: Int) {
    val r = if (x > 0) x else -x // marker: handOff-if
    suspendCoroutine { it.resume(r) }
}

suspend fun compareAfterMarker(a: Any): Boolean {
    val r: Any = suspendCoroutineUninterceptedOrReturn { a }
    return r === a // marker: compareAfterMarker-compare
}

suspend fun twoPoints(x: Int): Int {
    val a = pauseNow()
    val r = if (x > 0) a + x else a - x // marker: twoPoints-if
    val b = pauseNow()
    return r + b
}

suspend fun twoPointsSuspending(x: Int): Int {
    val a = pauseLater()
    val r = if (x > 0) a + x else a - x // marker: twoPointsSuspending-if
    val b = pauseLater()
    return r + b
}

suspend fun noPoint(x: Int): Int = if (x > 0) x else -x // marker: noPoint-if

class Holder {
    suspend fun member(x: Int): Int {
        val a = pauseNow()
        return if (x > a) x else a // marker: member-if
    }
}

class SuspendLabelHolder {
    private var label = 0

    suspend fun pick(): Int {
        pauseNow()
        return when (label) { // marker: label-when
            0 -> 10

            1 -> 20

            2 -> 30

            else -> 40
        }
    }
}

class NestedLabelOwner {
    class Config(
        @JvmField var label: Int,
    )

    suspend fun pick(config: Config): Int {
        pauseNow()
        return when (config.label) { // marker: nested-label-when
            0 -> 10

            1 -> 20

            2 -> 30

            else -> 40
        }
    }
}

private fun <T> runSuspend(block: suspend () -> T) {
    block.startCoroutine(
        object : Continuation<T> {
            override val context: CoroutineContext = EmptyCoroutineContext

            override fun resumeWith(result: Result<T>) {}
        },
    )
}

fun runTwoPoints(x: Int) = runSuspend { twoPoints(x) }

fun runTwoPointsSuspending(x: Int) = runSuspend { twoPointsSuspending(x) }

fun runNoPoint(x: Int) = runSuspend { noPoint(x) }

fun runHandOff(x: Int) = runSuspend { handOff(x) }

fun runMember(x: Int) = runSuspend { Holder().member(x) }

fun runLambda(x: Int) =
    runSuspend {
        val a = pauseNow()
        if (x > 0) a + x else a - x // marker: lambda-if
    }

class LabelHolder(
    @JvmField val label: Int,
)

fun plainSwitchOnLabel(holder: LabelHolder): String =
    when (holder.label) { // marker: plainSwitchOnLabel-switch
        0 -> "zero"

        1 -> "one"

        2 -> "two"

        else -> "other"
    }

fun plainReferenceCompare(
    a: Any,
    b: Any,
): Boolean = a === b // marker: plainReferenceCompare-compare

fun plainMarkerCompare(x: Any): Boolean = x === COROUTINE_SUSPENDED // marker: plainMarkerCompare-compare

suspend fun adopterMarkerCompare(x: Any): Boolean = x === COROUTINE_SUSPENDED // marker: adopterMarkerCompare-compare

suspend fun earlyHandOff(
    flag: Boolean,
    a: Any,
    b: Any,
) {
    if (flag) return handOff(1) // marker: earlyHandOff-if
    val c = a
    if (c === b) return // marker: earlyHandOff-compare
    handOff(2)
}

suspend fun compareRefs(
    a: Any,
    b: Any,
): Boolean {
    pauseNow()
    return a === b // marker: compareRefs-compare
}

fun runPlainSwitchOnLabel(label: Int): String = plainSwitchOnLabel(LabelHolder(label))

fun runPlainReferenceCompare(
    a: Any,
    b: Any,
): Boolean = plainReferenceCompare(a, b)

fun runCompareRefs(
    a: Any,
    b: Any,
) = runSuspend { compareRefs(a, b) }
