package com.example.target

fun conditionOnY(y: Int): Boolean = y > 0

fun conditionOnX(x: Int): Boolean = x > 0

fun withExtraLocal(x: Int): Boolean {
    val unused = 42
    return x > 0
}

fun withoutExtraLocal(x: Int): Boolean = x > 0

fun andCondition(
    a: Boolean,
    b: Boolean,
): Boolean {
    if (a && b) return true
    return false
}

fun ternaryCondition(
    x: Int,
    y: Int,
): Boolean {
    val bigger = if (x > y) x else y
    return bigger > 0
}

fun conditionAfterJump(
    a: Boolean,
    x: Int,
): Boolean {
    if (a) {
        sinkOnly(true)
    } else {
        sinkOnly(false)
    }
    return x > 0
}

fun conditionAtLoopHead(n: Int): Int {
    var i = 0
    var total = 0
    while (i < n) {
        if (i % 2 == 0) total += i
        i++
    }
    return total
}

fun conditionInCatch(x: Int): Int =
    try {
        risky(x)
    } catch (e: RuntimeException) {
        if (x > 0) 1 else -1
    }

private fun sinkOnly(a: Boolean) {
    if (a) return
}

private fun risky(x: Int): Int {
    if (x == 0) throw RuntimeException("zero")
    return x
}

fun apply(
    f: (Int) -> Boolean,
    v: Int,
): Boolean = f(v)

fun lambdaConditionOne(x: Int): Boolean {
    if (apply({ v -> v > 0 }, x)) return true
    return false
}

fun lambdaConditionTwo(x: Int): Boolean {
    if (apply({ v -> v > 0 }, x)) return true
    return false
}
