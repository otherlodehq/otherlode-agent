package dev.otherlode.instrumentation.endpoints.servlet

import dev.otherlode.bootstrap.OtherlodeEndpoints
import dev.otherlode.registry.RouteTemplateNormalizer

/** A servlet mapping read as an endpoint template: [template], of the seam's template kind [kind]. */
internal class Mapping(
    val template: String,
    val kind: Int,
)

/**
 * Reads [mapping] as one of the five forms the servlet specification defines, or returns null for
 * anything else, such as the regular expression specs Jetty accepts.
 *
 * The context root (the empty string) is the path template `/`. An extension mapping (`*.do`) and
 * the default mapping (`/`) are servlet pattern templates, kept as written. Any other mapping that
 * starts with a slash is exact, or a path mapping when it ends in a slash and a star, and is a path template
 * only when the shared normaliser leaves its identity meaning intact: [RouteTemplateNormalizer]
 * returns it unchanged, and no star or brace stands before that final slash and star. An exact mapping the normaliser
 * would change (a trailing slash, doubled slashes, a whole-segment `**` or `<name>`) or that holds
 * a star or a brace (`/api*`, `/a{b}`, which containers match literally) is a servlet pattern
 * template, kept as written, so it neither merges with another mapping nor reads as a wildcard or
 * a parameter.
 */
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
