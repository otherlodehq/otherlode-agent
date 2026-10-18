package dev.otherlode.instrumentation.endpoints.servlet

import dev.otherlode.bootstrap.OtherlodeEndpoints

/** A servlet mapping read as an endpoint template: [template], of the seam's template kind [kind]. */
internal class Mapping(
    val template: String,
    val kind: Int,
)

private const val FORBIDDEN_IN_PATH = "{}"

/**
 * Reads [mapping] as one of the five forms the servlet specification defines, or returns null for
 * anything else, such as the regular expression and URI template specs Jetty accepts.
 *
 * An exact mapping (`/x`) and a path mapping (a prefix followed by a slash and a star) are path
 * templates, and the context root (the empty string) is `/`. An extension mapping (`*.do`) and the
 * default mapping (`/`) are servlet pattern templates, kept as written.
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
            val extension = mapping.substring(2)
            if (extension.isNotEmpty() && extension.none { it == '*' || it == '/' || it in FORBIDDEN_IN_PATH }) {
                Mapping(mapping, OtherlodeEndpoints.TEMPLATE_SERVLET_PATTERN)
            } else {
                null
            }
        }

        mapping.startsWith("/") -> {
            val path = if (mapping.endsWith("/*")) mapping.dropLast(2) else mapping
            if (path.none { it == '*' || it in FORBIDDEN_IN_PATH }) Mapping(mapping, OtherlodeEndpoints.TEMPLATE_PATH) else null
        }

        else -> {
            null
        }
    }
