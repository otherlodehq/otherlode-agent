package dev.otherlode.dependencies

import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.nio.file.Path
import java.nio.file.Paths

/**
 * A class's code-source location, reduced to where its bytes live on disk. See [parse].
 */
internal sealed interface CodeSourceLocation {
    /** A file or directory, from a `file:` URL, or a `jar:file:<jar>!/` or `jar:nested:` URL naming no entry. */
    data class OnDisk(
        val path: Path,
    ) : CodeSourceLocation

    /**
     * An entry inside a jar on disk: a nested jar such as `BOOT-INF/lib/x.jar`, or a classes
     * directory such as `BOOT-INF/classes/`.
     */
    data class InJar(
        val outerJar: Path,
        val entryName: String,
    ) : CodeSourceLocation {
        /** Whether the entry is a directory of classes rather than a jar. */
        val isDirectory: Boolean
            get() = entryName.endsWith("/") || entryName in CLASSES_DIRECTORIES
    }

    /** A scheme this agent does not read, such as `jrt:` or `jar:http:`. */
    data object Unsupported : CodeSourceLocation

    companion object {
        private val CLASSES_DIRECTORIES = setOf("BOOT-INF/classes", "WEB-INF/classes")
        private const val FILE_PREFIX = "file:"
        private const val NESTED_PREFIX = "jar:nested:"
        private const val JAR_PREFIX = "jar:"
        private const val JAR_SEPARATOR = "!/"

        /**
         * Parses [location], a code-source URL's string form. Three shapes name bytes on disk:
         *
         * - `file:<path>`, a flat jar or a classes directory.
         * - `jar:nested:<outer>/!<entry>!/`, Spring Boot 3.2 and later. The path is percent-decoded
         *   and split at the last `/!`, and on Windows loses the slash before its drive letter, as
         *   Boot's `NestedLocation` does.
         * - `jar:file:<outer>!/<entry>!/`, Spring Boot 2 to 3.1, or `jar:file:<jar>!/` from a
         *   plain `URLClassLoader` given a `jar:` URL.
         *
         * Schemes are matched ignoring case, as the JDK matches them: `java.net.URL` lowercases only
         * the outermost one, so a `jar:FILE:` URL reaches here as written. Anything else is
         * [Unsupported]. A malformed `file:` URI throws.
         */
        fun parse(location: String): CodeSourceLocation =
            when {
                location.startsWith(FILE_PREFIX, ignoreCase = true) -> OnDisk(filePath(location))
                location.startsWith(NESTED_PREFIX, ignoreCase = true) -> parseNested(location.substring(NESTED_PREFIX.length))
                location.startsWith(JAR_PREFIX, ignoreCase = true) -> parseJarUrl(location.substring(JAR_PREFIX.length))
                else -> Unsupported
            }

        /**
         * The path a `file:` URL names. A loader that built its URLs with the deprecated
         * `File.toURL()` leaves characters such as a space unescaped, which `URI` rejects, so such
         * a URL is read as raw path text instead.
         */
        private fun filePath(fileUrl: String): Path =
            try {
                Paths.get(URI(fileUrl))
            } catch (_: URISyntaxException) {
                Paths.get(URI("file", null, fileUrl.substring(FILE_PREFIX.length), null))
            }

        private fun parseNested(rest: String): CodeSourceLocation {
            // Boot's UrlDecoder decodes percent escapes only, so a literal '+' must survive URLDecoder.
            val decoded = URLDecoder.decode(rest.removeSuffix(JAR_SEPARATOR).replace("+", "%2B"), Charsets.UTF_8)
            val split = decoded.lastIndexOf("/!")
            if (split < 0) return OnDisk(nestedPath(decoded))
            return InJar(nestedPath(decoded.substring(0, split)), decoded.substring(split + 2))
        }

        private fun nestedPath(path: String): Path = Path.of(if (File.separatorChar == '\\') windowsNestedPath(path) else path)

        /**
         * Drops the leading slash a `nested:` URL puts before a Windows drive letter (`/C:/app.jar`,
         * `///C:/app.jar`), as Boot's `NestedLocation` does before building a path on Windows.
         */
        internal fun windowsNestedPath(path: String): String =
            when {
                path.length > 2 && path[2] == ':' -> path.substring(1)
                path.startsWith("///") && path.length > 4 && path[4] == ':' -> path.substring(3)
                else -> path
            }

        private fun parseJarUrl(rest: String): CodeSourceLocation {
            val split = rest.indexOf(JAR_SEPARATOR)
            if (split < 0) return Unsupported
            val outer = rest.substring(0, split)
            if (!outer.startsWith(FILE_PREFIX, ignoreCase = true)) return Unsupported
            val outerPath = filePath(outer)
            val entry = rest.substring(split + JAR_SEPARATOR.length).removeSuffix(JAR_SEPARATOR)
            return if (entry.isEmpty()) OnDisk(outerPath) else InJar(outerPath, entry)
        }
    }
}

/**
 * A key two code-source locations share when they name the same root: a jar or a folder on disk,
 * or an entry of a jar such as a nested jar or `BOOT-INF/classes/`. The files are compared by real
 * path (a file that does not exist, by absolute path), and an entry by its name without a trailing
 * slash, since Spring Boot 3.2 and later writes `BOOT-INF/classes/` where earlier versions wrote
 * `BOOT-INF/classes`. Null for an [CodeSourceLocation.Unsupported] location.
 */
internal fun CodeSourceLocation.rootKey(): String? =
    when (this) {
        is CodeSourceLocation.OnDisk -> "disk:${realPath(path)}"
        is CodeSourceLocation.InJar -> "jar:${realPath(outerJar)}!/${entryName.trimEnd('/')}"
        CodeSourceLocation.Unsupported -> null
    }

/** The [rootKey] of a code-source location's string form, or null when it names no root this agent reads. */
internal fun rootKeyOf(location: String): String? =
    try {
        CodeSourceLocation.parse(location).rootKey()
    } catch (_: Exception) {
        null
    }

private fun realPath(path: Path): Path =
    try {
        path.toRealPath()
    } catch (_: IOException) {
        path.toAbsolutePath().normalize()
    }
