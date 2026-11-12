package com.example.demo.server

/** Loaded and never instantiated. */
class ReceiptPrinter(
    private val width: Int,
) {
    fun print(lines: List<String>): String = lines.joinToString("\n") { it.padEnd(width) }
}
