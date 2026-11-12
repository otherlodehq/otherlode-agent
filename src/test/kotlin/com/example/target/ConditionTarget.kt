package com.example.target

import java.io.File

class ConditionTarget(
    val limit: Int,
) {
    fun areEqualCheck(
        a: String?,
        b: String?,
    ): Int {
        if (a == b) return 1
        return 0
    }

    fun notEqualCheck(
        a: String?,
        b: String?,
    ): Int {
        if (a != b) return 1
        return 0
    }

    fun propertyCheck(other: ConditionTarget): Int {
        if (other.limit > 3) return 1
        return 0
    }

    fun javaGetterCheck(file: File): Int {
        if (file.name == "x") return 1
        return 0
    }

    fun javaIsGetterCheck(file: File): Int {
        if (file.isFile) return 1
        return 0
    }

    fun compareToCheck(
        a: Int,
        b: Int,
    ): Int {
        if (a.compareTo(b) > 0) return 1
        return 0
    }

    fun templateCheck(
        name: String,
        count: Int,
    ): Int {
        if ("$name-$count" == "x-1") return 1
        return 0
    }

    fun inlineCaller(amount: Int): Int = conditionInlineHelper(amount)

    fun companionCheck(): Int {
        if (ConditionHolder.threshold > 5) return 1
        return 0
    }

    fun objectCheck(): Int {
        if (ConditionSettings.enabled) return 1
        return 0
    }

    fun booleanCheck(flag: Boolean): Int {
        if (flag) return 1
        return 0
    }

    fun negatedBooleanCheck(flag: Boolean): Int {
        if (!flag) return 1
        return 0
    }

    fun intZeroCheck(count: Int): Int {
        if (count != 0) return 1
        return 0
    }

    fun isCheck(value: Any): Int {
        if (value is String) return 1
        return 0
    }

    fun notIsCheck(value: Any): Int {
        if (value !is String) return 1
        return 0
    }

    fun identityCheck(
        a: Any,
        b: Any,
    ): Int {
        if (a === b) return 1
        return 0
    }

    fun enumCheck(mode: ConditionMode): Int {
        if (mode == ConditionMode.FAST) return 1
        return 0
    }

    fun literalCheck(text: String): Int {
        if (text == "say \"hi\"\nbye") return 1
        return 0
    }

    fun doubleCheck(discounted: Double): Int {
        if (discounted > 100.0) return 1
        return 0
    }
}

fun ConditionTarget.extensionCheck(): Int {
    if (limit > 2) return 1
    return 0
}

fun getterCheck(target: ConditionTarget): Int {
    if (target.limit > 3) return 1
    return 0
}

fun narrowingCheck(total: Long): Int {
    if (total.toInt() > 3) return 1
    return 0
}

fun byteCheck(code: Int): Int {
    if (code.toByte() > 3) return 1
    return 0
}

fun wideningCheck(
    count: Int,
    limit: Long,
): Int {
    if (count < limit) return 1
    return 0
}

fun sameInstanceCheck(
    a: ConditionTarget,
    b: ConditionTarget,
): Int {
    if (a === b) return 1
    return 0
}

inline fun conditionInlineHelper(value: Int): Int {
    if (value > 10) return 1
    return 0
}

class ConditionHolder {
    companion object {
        val threshold: Int = System.getProperty("condition.threshold")?.length ?: 7
    }
}

object ConditionSettings {
    val enabled: Boolean = System.getProperty("condition.enabled") != null
}

enum class ConditionMode { FAST, SLOW }
