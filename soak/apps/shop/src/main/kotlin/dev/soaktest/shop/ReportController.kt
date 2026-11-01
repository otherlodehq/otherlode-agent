package dev.soaktest.shop

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class ReportController(
    private val reports: ReportService,
) {
    @GetMapping("/reports/monthly")
    fun monthly(): String = reports.monthly()

    @GetMapping("/reports/yearly")
    fun yearly(): String = reports.yearly()
}
