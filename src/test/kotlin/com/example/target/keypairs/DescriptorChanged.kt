package com.example.target.keypairs

class DescriptorChangedV1 {
    fun check(x: Int): String = if (x > 0) "pos" else "non-pos"
}

class DescriptorChangedV2 {
    fun check(
        x: Int,
        @Suppress("UNUSED_PARAMETER") extra: Int,
    ): String = if (x > 0) "pos" else "non-pos"
}
