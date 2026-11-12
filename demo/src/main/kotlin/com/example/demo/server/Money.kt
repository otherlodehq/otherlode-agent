package com.example.demo.server

/** An amount in pence with an unused overload. */
class Money(
    private val pence: Long,
) {
    constructor(pounds: Int, pence: Int) : this(pounds * 100L + pence)

    val amount: Double
        get() = pence / 100.0
}
