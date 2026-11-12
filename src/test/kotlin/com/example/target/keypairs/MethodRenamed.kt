package com.example.target.keypairs

class MethodRenamedV1 {
    fun check(x: Int): String = if (x > 0) "pos" else "non-pos"
}

class MethodRenamedV2 {
    fun verify(x: Int): String = if (x > 0) "pos" else "non-pos"
}
