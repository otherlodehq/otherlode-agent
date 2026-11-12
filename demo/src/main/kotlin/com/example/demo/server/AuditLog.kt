package com.example.demo.server

/** Loaded and never initialised. */
object AuditLog {
    private val entries = mutableListOf<String>()

    fun record(entry: String) {
        entries += entry
    }
}
