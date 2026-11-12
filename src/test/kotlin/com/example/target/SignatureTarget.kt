package com.example.target

import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

private fun formatTotal(
    total: Double,
    currency: String,
    decimals: Int,
): String = "%.${decimals}f $currency".format(total)

fun receipt(total: Double): String = formatTotal(total, "GBP", 2)

fun <T> firstOf(items: List<T>): T = items.first()

fun orders(): List<String> = listOf("a", "b")

fun String.shout(): String = uppercase() + "!"

fun mix(
    count: Long,
    ratio: Double,
    last: Int,
): String = "$count $ratio $last"

suspend fun loadName(id: Int): String {
    val offset = suspendCoroutineUninterceptedOrReturn<Int> { 1 }
    return "user-${id + offset}"
}

fun prefixAll(
    prefix: String,
    items: List<String>,
): List<String> = items.asSequence().map { prefix + it }.toList()

class SignatureTarget(
    private val id: Long,
    private val rate: Double,
) {
    fun describe(
        label: String,
        count: Int,
    ): String = "$label $id $rate $count"

    fun scale(
        factor: Long,
        weight: Double,
        tag: String,
    ): String = "$tag ${id * factor} ${rate * weight}"
}
