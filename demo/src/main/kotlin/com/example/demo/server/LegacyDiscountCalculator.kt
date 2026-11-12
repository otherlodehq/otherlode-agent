package com.example.demo.server

/** Guarded by a feature flag that is always off in this demo, so this class never loads at all. */
class LegacyDiscountCalculator {
    fun apply(total: Double): Double = total * 0.9
}
