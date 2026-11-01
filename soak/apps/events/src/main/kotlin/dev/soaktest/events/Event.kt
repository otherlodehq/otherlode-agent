package dev.soaktest.events

import kotlinx.serialization.Serializable

@Serializable
data class Event(
    val id: Long,
    val kind: String,
    val payload: String,
)
