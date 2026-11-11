package dev.otherlode.instrumentation.endpoints.servlet

import dev.otherlode.bootstrap.OtherlodeEndpoints
import dev.otherlode.registry.RouteTemplateNormalizer

internal class Mapping(
    val template: String,
    val kind: Int,
)

internal fun readMapping(mapping: String): Mapping? =
    when {
        mapping.isEmpty() -> {
            Mapping("/", OtherlodeEndpoints.TEMPLATE_PATH)
        }

        mapping == "/" -> {
            Mapping(mapping, OtherlodeEndpoints.TEMPLATE_SERVLET_PATTERN)
        }

        mapping.startsWith("*.") -> {
            if (mapping.length > 2 && '/' !in mapping) Mapping(mapping, OtherlodeEndpoints.TEMPLATE_SERVLET_PATTERN) else null
        }

        mapping.startsWith("/") -> {
            Mapping(mapping, if (isPathTemplate(mapping)) OtherlodeEndpoints.TEMPLATE_PATH else OtherlodeEndpoints.TEMPLATE_SERVLET_PATTERN)
        }

        else -> {
            null
        }
    }

private fun isPathTemplate(mapping: String): Boolean {
    val path = if (mapping.endsWith("/*")) mapping.dropLast(2) else mapping
    return path.none { it == '*' || it == '{' || it == '}' } && RouteTemplateNormalizer.normalize(mapping) == mapping
}
