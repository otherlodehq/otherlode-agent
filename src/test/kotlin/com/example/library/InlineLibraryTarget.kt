package com.example.library

inline fun libraryInline(value: Int): Int {
    if (value > 0) { // marker: libraryInline-if
        return value
    }
    return -value
}
