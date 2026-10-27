@file:OptIn(ExperimentalSerializationApi::class)

package com.example.target.kotlinc

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.KeepGeneratedSerializer
import kotlinx.serialization.Serializable

@KeepGeneratedSerializer
@Serializable(with = SerKeptSerializer::class)
class SerKept(
    val a: Int,
)

object SerKeptSerializer : KSerializer<SerKept> by SerKept.generatedSerializer()
