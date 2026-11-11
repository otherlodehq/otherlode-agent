package dev.otherlode.dependencies

import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.nio.file.Path
import java.nio.file.Paths

internal sealed interface CodeSourceLocation {
    data class OnDisk(
        val path: Path,
    ) : CodeSourceLocation

    data class InJar(
        val outerJar: Path,
        val entryName: String,
    ) : CodeSourceLocation {
        val isDirectory: Boolean
            get() = entryName.endsWith("/") || entryName in CLASSES_DIRECTORIES
    }

    data object Unsupported : CodeSourceLocation

    companion object {
        private val CLASSES_DIRECTORIES = setOf("BOOT-INF/classes", "WEB-INF/classes")
        private const val FILE_PREFIX = "file:"
        private const val NESTED_PREFIX = "jar:nested:"
        private const val JAR_PREFIX = "jar:"
        private const val JAR_SEPARATOR = "!/"

        fun parse(location: String): CodeSourceLocation =
            when {
                location.startsWith(FILE_PREFIX, ignoreCase = true) -> OnDisk(filePath(location))
                location.startsWith(NESTED_PREFIX, ignoreCase = true) -> parseNested(location.substring(NESTED_PREFIX.length))
                location.startsWith(JAR_PREFIX, ignoreCase = true) -> parseJarUrl(location.substring(JAR_PREFIX.length))
                else -> Unsupported
            }

        private fun filePath(fileUrl: String): Path =
            try {
                Paths.get(URI(fileUrl))
            } catch (_: URISyntaxException) {
                Paths.get(URI("file", null, fileUrl.substring(FILE_PREFIX.length), null))
            }

        private fun parseNested(rest: String): CodeSourceLocation {
            // Boot decodes percent escapes only, so a literal '+' must survive URLDecoder.
            val decoded = URLDecoder.decode(rest.removeSuffix(JAR_SEPARATOR).replace("+", "%2B"), Charsets.UTF_8)
            val split = decoded.lastIndexOf("/!")
            if (split < 0) return OnDisk(nestedPath(decoded))
            return InJar(nestedPath(decoded.substring(0, split)), decoded.substring(split + 2))
        }

        private fun nestedPath(path: String): Path = Path.of(if (File.separatorChar == '\\') windowsNestedPath(path) else path)

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

internal fun CodeSourceLocation.rootKey(): String? =
    when (this) {
        is CodeSourceLocation.OnDisk -> "disk:${realPath(path)}"
        is CodeSourceLocation.InJar -> "jar:${realPath(outerJar)}!/${entryName.trimEnd('/')}"
        CodeSourceLocation.Unsupported -> null
    }

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
