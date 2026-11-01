package dev.soaktest.shop

data class OrderView(
    val id: Long,
    val total: Double,
    val label: String,
)
