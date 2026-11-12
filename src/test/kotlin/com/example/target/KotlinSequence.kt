package com.example.target.outsidecaller

class KotlinSequence : CharSequence {
    override val length: Int
        get() = 0

    override fun get(index: Int): Char = 'a'

    override fun subSequence(
        startIndex: Int,
        endIndex: Int,
    ): CharSequence = ""
}
