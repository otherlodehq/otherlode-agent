package com.example.demo.server

/**
 * Guarded the same way as [LegacyDiscountCalculator]. The environment flag that reaches it is
 * always off in this demo, so this object never loads either.
 */
object LegacyRates {
    @JvmField
    val FLAT_FEE: Double = 1.5
}
