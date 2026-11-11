package dev.otherlode.config

import dev.otherlode.agentLogger
import java.lang.System.Logger.Level

internal object ServiceIdentityValues {
    // Must equal ingest.MaxResourceValueBytes in otherlode-collector, which rejects longer values.
    const val MAX_VALUE_BYTES = 1024

    private val log by agentLogger(ServiceIdentityValues::class.java.name)

    fun isDotSegment(value: String): Boolean = value == "." || value == ".."

    fun usable(
        value: String?,
        source: String,
    ): String? {
        if (value != null && isDotSegment(value)) {
            log.log(Level.WARNING, "otherlode: ignoring '$value' from $source, since no URL path can name a service or namespace '$value'")
            return null
        }
        return withinLimit(value, source)
    }

    fun withinLimit(
        value: String?,
        source: String,
    ): String? {
        if (value != null && value.contains('\u0000')) {
            log.log(Level.WARNING, "otherlode: ignoring the value from $source, since it holds a NUL character")
            return null
        }
        if (value == null || value.toByteArray(Charsets.UTF_8).size <= MAX_VALUE_BYTES) return value
        log.log(Level.WARNING, "otherlode: ignoring the value from $source, since it is longer than $MAX_VALUE_BYTES bytes in UTF-8")
        return null
    }
}
