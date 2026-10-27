@file:OptIn(InternalSerializationApi::class)

package com.example.target.kotlinc

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.Serializable

// An adopter's own class named $serializer inside a @Serializable class. Only the K1 frontend
// accepts it; the plugin fills it in.
@Serializable
class SerHandDeclared(
    val a: Int,
) {
    @Suppress("ktlint:standard:class-naming")
    class `$serializer` {
        fun extra() = 1
    }
}
