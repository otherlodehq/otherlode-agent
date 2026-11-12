package com.example.demo.spring

import tools.jackson.databind.json.JsonMapper

/**
 * Guarded by a feature flag that is always off in this demo, so this class never loads at all.
 *
 * It is also the only class in this demo that names Jackson.
 */
class LegacyPricing {
    /** Applies the legacy discount. */
    fun apply(price: Double): Double {
        val mapper = JsonMapper.shared()
        val stored = mapper.writeValueAsString(price * 0.95)
        return mapper.readValue(stored, Double::class.java)
    }
}
