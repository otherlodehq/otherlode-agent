package dev.otherlode.config

import java.lang.System.Logger.Level

/**
 * Screens the values a service name, namespace, version, environment or instance ID is read from. The
 * server shows a service at a URL path that holds its namespace and its name as segments, and
 * browsers drop a `.` or `..` segment even when it is percent-escaped. So neither value can name a
 * service, and the collector rejects a payload that carries one. The collector also rejects a
 * payload that carries any of these values over [MAX_VALUE_BYTES] bytes, or holding a NUL
 * (U+0000).
 */
internal object ServiceIdentityValues {
    /**
     * The longest identity value the collector accepts, in UTF-8 bytes. It is the collector's limit,
     * `ingest.MaxResourceValueBytes` in otherlode-collector.
     */
    const val MAX_VALUE_BYTES = 1024

    private val log = System.getLogger(ServiceIdentityValues::class.java.name)

    fun isDotSegment(value: String): Boolean = value == "." || value == ".."

    /**
     * Returns [value], or null when it is `.` or `..`, holds a NUL or is longer than [MAX_VALUE_BYTES]. A skipped
     * value logs a WARNING that names [source], so the next source can give the value instead.
     */
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

    /**
     * Returns [value], or null when it holds a NUL or is longer than [MAX_VALUE_BYTES] in UTF-8. A
     * skipped value logs a WARNING that names [source], never the value.
     */
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
