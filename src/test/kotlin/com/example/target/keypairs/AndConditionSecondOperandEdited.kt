package com.example.target.keypairs

class AndConditionSecondOperandEditedV1 {
    fun check(
        a: Int,
        b: Int,
    ): String = if (a > 0 && b > 0) "both" else "not-both"
}

class AndConditionSecondOperandEditedV2 {
    fun check(
        a: Int,
        b: Int,
    ): String = if (a > 0 && b >= 0) "both" else "not-both"
}
