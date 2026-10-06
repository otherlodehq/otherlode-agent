package com.example.target.outsidecaller

/**
 * A Kotlin `CharSequence`. kotlinc implements `length()I` and `charAt(I)C` as bridges that call
 * `getLength()I` and `get(I)C`, methods of another name.
 */
class KotlinSequence : CharSequence {
    override val length: Int
        get() = 0

    override fun get(index: Int): Char = 'a'

    override fun subSequence(
        startIndex: Int,
        endIndex: Int,
    ): CharSequence = ""
}
