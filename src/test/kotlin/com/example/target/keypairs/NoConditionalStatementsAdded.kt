package com.example.target.keypairs

class NoConditionalStatementsAddedV1 {
    fun check(x: Int): String = if (x > 0) "pos" else "non-pos"
}

class NoConditionalStatementsAddedV2 {
    fun check(x: Int): String {
        println("checking")
        var extra = 1
        extra = extra + 1
        return if (x > 0) "pos" else "non-pos"
    }
}
