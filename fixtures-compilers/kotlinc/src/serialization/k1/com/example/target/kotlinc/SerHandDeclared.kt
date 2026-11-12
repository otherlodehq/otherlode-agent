@file:OptIn(InternalSerializationApi::class)

package com.example.target.kotlinc

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.Serializable

// Only the K1 frontend accepts a hand-declared $serializer class inside a @Serializable class.
@Serializable
class SerHandDeclared(
    val a: Int,
) {
    @Suppress("ktlint:standard:class-naming")
    class `$serializer` {
        fun extra() = 1
    }
}
