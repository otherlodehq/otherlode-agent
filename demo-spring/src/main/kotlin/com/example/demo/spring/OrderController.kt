package com.example.demo.spring

import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

// Each odd shape in the Spring demo is deliberate: the demo report shows a finding for it.

/**
 * A component-scanned controller bean, registered through Spring's bean-name path.
 *
 * The demo client calls [get], [create], and [ping]. [invoice] and [delete] are declared but
 * never called, and [InvoiceService.render], reachable only through [invoice], is never hit at
 * the method tier either.
 */
@RestController
class OrderController(
    private val invoiceService: InvoiceService,
    private val priceFormatter: PriceFormatter,
) {
    @GetMapping("/orders/{id}")
    fun get(
        @PathVariable id: String,
    ): String {
        val price =
            if (System.getenv("OTHERLODE_DEMO_LEGACY_PRICING") == "true") {
                LegacyPricing().apply(100.0)
            } else {
                100.0
            }
        return "order $id price=${priceFormatter.withTax(price)}"
    }

    @PostMapping("/orders")
    fun create(): String = "order created"

    @GetMapping("/orders/{id}/invoice")
    fun invoice(
        @PathVariable id: String,
    ): String = invoiceService.render(id)

    @DeleteMapping("/orders/{id}")
    fun delete(
        @PathVariable id: String,
    ): String = "order $id deleted"

    @RequestMapping("/ping")
    fun ping(): String = "pong"
}
