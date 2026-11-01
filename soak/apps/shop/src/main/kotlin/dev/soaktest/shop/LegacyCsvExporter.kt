package dev.soaktest.shop

import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser

class LegacyCsvExporter {
    fun export(rows: List<List<String>>): String {
        val out = StringBuilder()
        val printer = CSVFormat.DEFAULT.print(out)
        rows.forEach { printer.printRecord(it) }
        printer.flush()
        return out.toString()
    }

    fun parse(text: String): Int = CSVParser.parse(text, CSVFormat.DEFAULT).records.size
}
