package com.example.target.keypairs

class IdenticalConditionAddedV1 {
    fun check(x: Int): String {
        if (x > 0) return "pos"
        return "non-pos"
    }
}

class IdenticalConditionAddedV2 {
    fun check(x: Int): String {
        if (x > 0) return "pos"
        if (x > 0) return "still-pos"
        return "non-pos"
    }
}
