package dev.soaktest.shop

import org.springframework.stereotype.Component

@Component
class Pricing {
    fun price(
        base: Double,
        rate: Double,
    ): Double = base * (1 - rate)

    fun applyLegacyDiscount(
        amount: Double,
        years: Int,
    ): Double = amount * (1 - 0.01 * years)
}
