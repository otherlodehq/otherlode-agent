package dev.otherlode.export

import dev.otherlode.agentLogger
import java.lang.System.Logger.Level
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Logs the `Otherlode-Notice` values a receiver sends with a 202, at most once an hour per shown
 * value. Exporters that share one instance share the limit, so a JVM logs a notice once an hour
 * however many exporters it has. It keeps at most [MAX_VALUES] values, and shows any further value
 * as `unknown`. [nanoTime] is the clock; tests replace it.
 */
class IngestNotices(
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val log by agentLogger(IngestNotices::class.java.name)
    private val lastLogged = ConcurrentHashMap<String, Long>()

    /** Logs a warning for each notice in [headerValues], one per header line or comma-joined list. */
    fun report(headerValues: List<String>) {
        for (line in headerValues) {
            for (part in line.split(',')) {
                val value = part.trim()
                if (value.isEmpty()) continue
                val shown = shown(value)
                if (due(shown)) log.log(Level.WARNING, message(shown))
            }
        }
    }

    private fun shown(value: String): String =
        when {
            value in KNOWN -> value
            !wellFormed(value) -> UNKNOWN
            lastLogged.containsKey(value) || lastLogged.size < MAX_VALUES -> value
            else -> UNKNOWN
        }

    private fun due(shown: String): Boolean {
        val now = nanoTime()
        var allowed = false
        lastLogged.compute(shown) { _, last ->
            if (last == null || now - last >= HOUR_NANOS) {
                allowed = true
                now
            } else {
                last
            }
        }
        return allowed
    }

    private fun message(shown: String): String =
        when (shown) {
            SERVICE_OVER_CAP -> {
                "otherlode: Otherlode stores this service's payloads but does not track the service, " +
                    "because the tenant's plan tracks no more services"
            }

            ENVIRONMENT_NOT_TRACKED -> {
                "otherlode: Otherlode stores this environment's payloads but does not track the environment, " +
                    "because the service already watches as many environments as its cap allows"
            }

            else -> {
                "otherlode: Otherlode sent a notice this agent does not know: $shown"
            }
        }

    private fun wellFormed(value: String): Boolean = value.length <= MAX_BYTES && value.all { it in 'a'..'z' || it == '-' }

    private companion object {
        const val SERVICE_OVER_CAP = "service-over-cap"
        const val ENVIRONMENT_NOT_TRACKED = "environment-not-tracked"
        const val UNKNOWN = "unknown"
        const val MAX_BYTES = 64
        const val MAX_VALUES = 32
        val KNOWN = setOf(SERVICE_OVER_CAP, ENVIRONMENT_NOT_TRACKED)
        val HOUR_NANOS = TimeUnit.HOURS.toNanos(1)
    }
}
