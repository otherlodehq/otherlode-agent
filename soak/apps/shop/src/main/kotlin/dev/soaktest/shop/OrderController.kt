package dev.soaktest.shop

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.concurrent.atomic.AtomicReference

@RestController
class OrderController(
    private val orders: OrderService,
) {
    private val search = OrderSearch()
    private val lastFallback = AtomicReference<() -> List<String>>()

    @GetMapping("/orders/{id}")
    fun get(
        @PathVariable id: Long,
    ): OrderView = orders.find(id)

    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @RequestParam(defaultValue = "widget") q: String,
    ): List<String> {
        val fallback = { listOf("no match for $q") }
        lastFallback.set(fallback)
        return search.search(q, limit = 5)
    }

    @DeleteMapping("/orders/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        @PathVariable id: Long,
    ) {
    }
}
