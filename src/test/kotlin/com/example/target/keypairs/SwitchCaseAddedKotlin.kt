package com.example.target.keypairs

class SwitchCaseAddedKotlinV1 {
    fun classify(x: Int): String =
        when (x) {
            1 -> "one"
            2 -> "two"
            else -> "other"
        }
}

class SwitchCaseAddedKotlinV2 {
    fun classify(x: Int): String =
        when (x) {
            1 -> "one"
            2 -> "two"
            3 -> "three"
            else -> "other"
        }
}
