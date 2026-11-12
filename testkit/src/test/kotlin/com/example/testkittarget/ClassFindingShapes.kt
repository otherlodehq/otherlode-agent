package com.example.testkittarget

object AuditTrail {
    private val entries = mutableListOf<String>()

    fun record(entry: String) {
        entries += entry
    }
}

class LinePrinter(
    private val width: Int,
) {
    fun print(lines: List<String>): String = lines.joinToString("\n") { it.padEnd(width) }
}

class ReportWriter(
    private val width: Int,
) {
    fun write(line: String): String = TextUtil().pad(line, width)

    companion object {
        @JvmStatic
        fun footer(): String = "end"
    }
}

class TextUtil {
    fun trim(text: String): String = text.trim()

    fun pad(
        text: String,
        width: Int,
    ): String = text.padEnd(width)
}

class Amount(
    private val pence: Long,
) {
    constructor(pounds: Int, pence: Int) : this(pounds * 100L + pence)

    fun inPounds(): Double = pence / 100.0
}

class Scaled
    @JvmOverloads
    constructor(
        amount: Double,
        scale: Int = 2,
    ) {
        val value: Double = amount * scale
    }

class Utils private constructor() {
    companion object {
        @JvmStatic
        fun name(): String = "utils"
    }
}

object Counters {
    fun size(): Int = 0

    fun unused(): Int = 1
}

interface Greeter {
    fun greet(): String = "hello"
}
