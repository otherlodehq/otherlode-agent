package dev.otherlode.instrumentation.endpoints.vertxweb

private const val NARROW_NAME = "[A-Za-z0-9_]+"
private const val EXTENDED_NAME = "[A-Za-z_\$][A-Za-z0-9_\$-]*"
private const val EXTENDED_PATTERN_PROPERTY = "io.vertx.web.route.param.extended-pattern"

private fun parameter(name: String) = Regex(":($name)(?:::$name)?")

private val narrowParameter = parameter(NARROW_NAME)
private val extendedParameter = parameter(EXTENDED_NAME)

/**
 * Turns Vert.x Web's `:name` and 5.2's `:name::type` into `{name}`. The name pattern is the one
 * Vert.x reads its own paths with, including the `io.vertx.web.route.param.extended-pattern`
 * system property.
 */
internal fun toTemplate(path: String): String {
    val pattern = if (java.lang.Boolean.getBoolean(EXTENDED_PATTERN_PROPERTY)) extendedParameter else narrowParameter
    return pattern.replace(path) { "{${it.groupValues[1]}}" }
}

/**
 * The mount point a sub-router mounted on [path] sees inside a parent whose mount point is
 * [parent], composed as `RoutingContextWrapper` composes it.
 */
internal fun composeMountPoint(
    parent: String?,
    path: String,
): String =
    when {
        parent == null -> path
        path == "/" -> parent
        parent == "/" -> path
        parent.endsWith("/") -> parent.dropLast(1) + path
        else -> parent + path
    }

private fun joinPath(
    prefix: String?,
    path: String,
): String =
    when {
        prefix == null -> path
        path == "/" -> prefix
        prefix.endsWith("/") -> prefix + path.substring(1)
        else -> prefix + path
    }

/**
 * The template of a path route under [prefix], the mount path chain it is reached through, or
 * null at a root router. [path] is `Route.getPath()`, which has lost the trailing `*` of a path
 * that is not [exact]. A route with no path matches everything under its mount.
 */
internal fun pathTemplate(
    prefix: String?,
    path: String?,
    exact: Boolean,
): String {
    if (path == null || (!exact && path == "/")) return toTemplate(prefix.orEmpty().trimEnd('/') + "/*")
    val joined = joinPath(prefix, path)
    return toTemplate(if (exact) joined else "$joined*")
}

/** The template of a regex route: the mount path chain's prefix followed by the regex source as written. */
internal fun regexTemplate(
    prefix: String?,
    source: String,
): String = toTemplate(prefix.orEmpty().trimEnd('/')) + source
