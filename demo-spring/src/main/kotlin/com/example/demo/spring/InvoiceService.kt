package com.example.demo.spring

import org.springframework.stereotype.Service

/**
 * Reached only through [OrderController]'s invoice endpoint, which the demo client never calls.
 * Loaded but never hit.
 */
@Service
class InvoiceService {
    fun render(id: String): String = "invoice for order $id"
}
