package com.example.demo.spring

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** The one thing in this demo that makes Spring generate a CGLIB proxy in the adopter's own package. */
@Configuration
class PricingConfiguration {
    @Bean
    fun taxRate(): TaxRate = TaxRate(0.2)

    @Bean
    fun priceFormatter(): PriceFormatter = PriceFormatter(taxRate())
}

/** The rate [PriceFormatter] charges. */
data class TaxRate(
    val fraction: Double,
)

/** Applies [rate] to a pre-tax price. */
class PriceFormatter(
    private val rate: TaxRate,
) {
    fun withTax(price: Double): String = "%.2f".format(price * (1 + rate.fraction))
}
