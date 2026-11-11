package dev.otherlode.config

import dev.otherlode.agentLogger
import java.io.ByteArrayOutputStream
import java.lang.System.Logger.Level

internal class OtelResourceSettings(
    private val serviceNameSetting: String?,
    private val attributes: Map<String, String>,
) {
    val serviceName: String? =
        ServiceIdentityValues.usable(serviceNameSetting, "the otel.service.name setting")
            ?: ServiceIdentityValues.usable(attributes["service.name"], "service.name in the resource attributes")

    val serviceNamespace: String? =
        ServiceIdentityValues.usable(attributes["service.namespace"], "service.namespace in the resource attributes")

    val serviceVersion: String? =
        ServiceIdentityValues.withinLimit(attributes["service.version"], "service.version in the resource attributes")

    val environment: String? =
        ServiceIdentityValues.withinLimit(
            attributes["deployment.environment.name"],
            "deployment.environment.name in the resource attributes",
        )
            ?: ServiceIdentityValues.withinLimit(attributes["deployment.environment"], "deployment.environment in the resource attributes")

    companion object {
        private val log by agentLogger(OtelResourceSettings::class.java.name)

        fun resolve(
            systemProperties: (String) -> String?,
            env: (String) -> String?,
        ): OtelResourceSettings {
            val serviceName = nonBlank(systemProperties("otel.service.name")) ?: nonBlank(env("OTEL_SERVICE_NAME"))
            val attributesFromProperty = nonBlank(systemProperties("otel.resource.attributes"))
            val attributes =
                if (attributesFromProperty != null) {
                    parseAttributes(attributesFromProperty, "the otel.resource.attributes system property")
                } else {
                    parseAttributes(env("OTEL_RESOURCE_ATTRIBUTES"), "OTEL_RESOURCE_ATTRIBUTES")
                }
            return OtelResourceSettings(serviceName, attributes)
        }

        private fun nonBlank(raw: String?): String? = raw?.trim()?.ifBlank { null }

        fun parseAttributes(
            raw: String?,
            source: String,
        ): Map<String, String> {
            if (raw.isNullOrBlank()) return emptyMap()
            val attributes = LinkedHashMap<String, String>()
            for (entry in raw.split(',')) {
                val pair = entry.trim()
                if (pair.isEmpty()) continue
                val separator = pair.indexOf('=')
                val key = if (separator < 0) "" else pair.take(separator).trim()
                if (key.isEmpty()) {
                    log.log(
                        Level.WARNING,
                        "otherlode: ignoring all of $source, since its entry '$pair' is not key=value; " +
                            "no service name, namespace, version or environment is read from it",
                    )
                    return emptyMap()
                }
                val value = percentDecode(pair.substring(separator + 1).trim()).trim()
                if (value.isEmpty()) continue
                attributes[key] = value
            }
            return attributes
        }

        fun percentDecode(value: String): String {
            if (value.indexOf('%') < 0) return value
            val decoded = StringBuilder(value.length)
            val bytes = ByteArrayOutputStream()
            var i = 0
            while (i < value.length) {
                val c = value[i]
                val high = if (c == '%' && i + 2 < value.length) Character.digit(value[i + 1], 16) else -1
                val low = if (high >= 0) Character.digit(value[i + 2], 16) else -1
                if (low >= 0) {
                    bytes.write((high shl 4) + low)
                    i += 3
                    continue
                }
                if (bytes.size() > 0) {
                    decoded.append(bytes.toString(Charsets.UTF_8))
                    bytes.reset()
                }
                decoded.append(c)
                i++
            }
            if (bytes.size() > 0) decoded.append(bytes.toString(Charsets.UTF_8))
            return decoded.toString()
        }
    }
}
