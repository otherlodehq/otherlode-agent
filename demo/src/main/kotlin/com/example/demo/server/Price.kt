package com.example.demo.server

import kotlin.math.pow
import kotlin.math.round

/** An amount rounded to [scale] decimal places. */
class Price
    @JvmOverloads
    constructor(
        amount: Double,
        scale: Int = 2,
    ) {
        val amount: Double = round(amount * 10.0.pow(scale)) / 10.0.pow(scale)
    }
