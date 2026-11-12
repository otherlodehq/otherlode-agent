package com.example.target.keypairs

class ConditionEditedV1 {
    fun check(x: Int): String = if (x > 0) "pos" else "non-pos"
}

class ConditionEditedV2 {
    fun check(x: Int): String = if (x >= 0) "pos" else "non-pos"
}
