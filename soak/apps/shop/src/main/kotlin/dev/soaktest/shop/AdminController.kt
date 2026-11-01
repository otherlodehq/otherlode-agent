package dev.soaktest.shop

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class AdminController {
    @GetMapping("/admin/rebuild")
    fun rebuild(): String = LegacyCsvExporter().export(listOf(listOf("id", "total"), listOf("1", "2.0")))
}
