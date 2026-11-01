package dev.soaktest.shop

class OldPromotions {
    fun discountFor(code: String): Double = if (code == "SPRING") 0.15 else 0.0

    fun expire(code: String): String = "expired $code"
}
