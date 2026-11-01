package dev.soaktest.shop

import com.google.common.base.Joiner
import org.springframework.stereotype.Service

@Service
class OrderService(
    private val pricing: Pricing,
) {
    fun find(id: Long): OrderView {
        if (id < 0) {
            return OrderView(0, 0.0, "none")
        }
        val tier = Tier.entries[(id % 3).toInt()]
        val rate =
            when (tier) {
                Tier.BRONZE -> 0.0
                Tier.SILVER -> 0.05
                Tier.GOLD -> 0.1
                Tier.PLATINUM -> 0.2
            }
        val total = pricing.price(100.0 + id, rate)
        val label = Joiner.on("-").join("order", id, tier.name.lowercase())
        AuditRecord.next()
        return OrderView(id, total, label)
    }
}
