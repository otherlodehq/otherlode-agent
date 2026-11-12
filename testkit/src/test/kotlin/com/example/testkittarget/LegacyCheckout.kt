package com.example.testkittarget

class LegacyCheckout {
    fun total(
        amount: Double,
        legacy: Boolean,
    ): Double =
        if (legacy) {
            LegacyCalculator().apply(amount) + LegacyFees.FLAT_FEE
        } else {
            amount
        }
}

class LegacyCalculator {
    fun apply(amount: Double): Double = amount * 0.9
}

object LegacyFees {
    @JvmField
    val FLAT_FEE: Double = 1.5
}
