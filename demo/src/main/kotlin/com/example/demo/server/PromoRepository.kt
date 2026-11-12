package com.example.demo.server

internal object PromoRepository {
    fun find(code: String): Int = if (code.isBlank()) 0 else code.length
}
