package com.example.target.keypairs

class VariableRenamedV1 {
    fun check(x: Int): String = if (x > 0) "pos" else "non-pos"
}

class VariableRenamedV2 {
    fun check(value: Int): String = if (value > 0) "pos" else "non-pos"
}
