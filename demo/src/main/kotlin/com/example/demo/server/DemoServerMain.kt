package com.example.demo.server

import com.example.demo.DemoPorts
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

// Each odd shape in the demo server is deliberate, because the demo report shows a finding for it.
private const val FREE_SHIPPING_THRESHOLD = 100.0

/**
 * Two endpoints and one feature-flagged class.
 *
 * [com.example.demo.client] exercises `/checkout`'s free-shipping branch only one way. `/promo` is
 * reachable, but nothing calls it.
 */
fun main() {
    val server = HttpServer.create(InetSocketAddress(DemoPorts.SERVER_PORT), 0)
    server.createContext("/checkout", ::handleCheckout)
    server.createContext("/promo", PromoHandler())
    server.createContext("/__shutdown") { exchange ->
        respond(exchange, "shutting down")
        Thread { System.exit(0) }.start()
    }
    server.start()
    println("otherlode demo server listening on ${DemoPorts.SERVER_PORT}")
    // A class literal loads a class without initialising it.
    println(
        "otherlode demo server can audit with ${AuditLog::class.java.simpleName} and print with ${ReceiptPrinter::class.java.simpleName}",
    )
}

private fun handleCheckout(exchange: HttpExchange) {
    val total = totalParam(exchange)
    val discounted =
        if (System.getenv("ENABLE_LEGACY_DISCOUNT") == "true") {
            LegacyDiscountCalculator().apply(total) + LegacyRates.FLAT_FEE
        } else {
            total
        }
    val charged = Price(Money(Math.round(discounted * 100)).amount, scale = 2).amount
    val message =
        if (discounted > FREE_SHIPPING_THRESHOLD) {
            "${describeOrder(charged)} qualifies for free shipping"
        } else {
            "${describeOrder(charged)} does not qualify for free shipping"
        }
    val send: (HttpExchange, String) -> Unit = ::respond
    send(exchange, "${checkoutGreeting()}: $message")
}

private inline fun describeOrder(total: Double): String = "order of ${formatTotal(total, decimals = 2)}"

private fun formatTotal(
    total: Double,
    currency: String = "GBP",
    decimals: Int = 2,
): String = "%.${decimals}f %s".format(total, currency)

private class PromoHandler : HttpHandler {
    override fun handle(exchange: HttpExchange) {
        respond(exchange, applyPromoCode(exchange.requestURI.query ?: ""))
    }
}

private fun applyPromoCode(code: String): String {
    val discount = PromoRepository.find(code)
    return "promo code applied: $discount% off"
}

private fun totalParam(exchange: HttpExchange): Double {
    val query = exchange.requestURI.query ?: return 0.0
    val value =
        query
            .split("&")
            .map { it.split("=", limit = 2) }
            .firstOrNull { it.first() == "total" }
            ?.getOrNull(1)
    return value?.toDoubleOrNull() ?: 0.0
}

private fun respond(
    exchange: HttpExchange,
    message: String,
) {
    val bytes = message.toByteArray(StandardCharsets.UTF_8)
    exchange.sendResponseHeaders(200, bytes.size.toLong())
    exchange.responseBody.use { it.write(bytes) }
}
