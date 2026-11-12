package com.example.target.keypairs

class StringConcatConstantEditV1 {
    fun check(id: Int): Int = if (("user:" + id).length > 7) 1 else 0
}

class StringConcatConstantEditV2 {
    fun check(id: Int): Int = if (("account:" + id).length > 7) 1 else 0
}
