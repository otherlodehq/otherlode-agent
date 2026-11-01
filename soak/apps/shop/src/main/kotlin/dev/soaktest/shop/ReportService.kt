package dev.soaktest.shop

import org.springframework.stereotype.Service

@Service
class ReportService {
    fun monthly(): String = "monthly report"

    fun yearly(): String = "yearly report"
}
