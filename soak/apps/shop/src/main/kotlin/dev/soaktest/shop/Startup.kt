package dev.soaktest.shop

import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component

@Component
class Startup : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        TaxTables::class.java.name
        ShutdownAudit.armed()
        Class.forName("org.apache.commons.csv.CSVFormat", false, javaClass.classLoader)
    }
}
