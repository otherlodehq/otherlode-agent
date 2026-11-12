package com.example.target

inline fun guardInlineHelper(value: Int): Int {
    val doubled = value * 2 // marker: guardInlineHelper-body
    return doubled + 1 // marker: guardInlineHelper-return
}
