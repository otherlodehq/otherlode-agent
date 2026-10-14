package dev.otherlode.dependencies

import java.io.ByteArrayOutputStream
import java.io.File
import java.net.MalformedURLException
import java.net.URL
import java.util.StringTokenizer
import java.util.jar.Attributes
import java.util.jar.JarFile
import java.util.jar.Manifest

private const val BOOT_LIB = "Spring-Boot-Lib"
private const val WAR_PREFIX = "WEB-INF/"
private const val WAR_LAUNCHER = ".WarLauncher"
private val JAR_LIB_PREFIXES = listOf("BOOT-INF/lib/")
private val WAR_LIB_PREFIXES = listOf("WEB-INF/lib/", "WEB-INF/lib-provided/")

/**
 * The entry prefixes Spring Boot's launcher reads library jars from, for a fat jar or an exploded
 * one with these manifest [attributes]: `BOOT-INF/lib/`, or `WEB-INF/lib/` and
 * `WEB-INF/lib-provided/` for an executable war.
 */
internal fun bootLibPrefixes(attributes: Attributes): List<String> {
    val isWar =
        attributes.getValue(BOOT_LIB)?.trim()?.startsWith(WAR_PREFIX) == true ||
            attributes.getValue("Main-Class")?.trim()?.endsWith(WAR_LAUNCHER) == true
    return if (isWar) WAR_LIB_PREFIXES else JAR_LIB_PREFIXES
}

/**
 * The nested jars Spring Boot's launcher puts on the classpath, in the outer jar's entry order:
 * every entry that is not a directory under one of [bootLibPrefixes], at any depth and whatever
 * its extension.
 *
 * The classpath index is not consulted. `Launcher.getClassPathIndex` returns null unless the
 * archive is exploded, so a packaged `java -jar` launch ignores it, and `JarLauncher` and
 * `WarLauncher` select these prefixes themselves in `isLibraryFileOrClassesDirectory` (read from
 * the loader bytecode Boot 4.1.1 packages). The index can leave a jar out that still runs, such as
 * `spring-boot-jarmode-tools`.
 *
 * Shared by the dependency listing and the class listing, so both open the same nested jars.
 */
internal fun nestedJarNames(
    jarFile: JarFile,
    attributes: Attributes,
): List<String> {
    val prefixes = bootLibPrefixes(attributes)
    return jarFile
        .entries()
        .asSequence()
        .filter { !it.isDirectory && prefixes.any { prefix -> it.name.startsWith(prefix) } }
        .map { it.name }
        .toList()
}

/**
 * A file a `Class-Path` attribute names. [isDirectory] is true when its URL ends in `/`: the JDK
 * reads such an entry only as a directory of classes, and any other entry only as a jar, whatever
 * is on disk at [file].
 */
internal data class ClassPathEntry(
    val file: File,
    val isDirectory: Boolean,
)

/**
 * The files [manifest]'s `Class-Path` names, resolved the way the JDK's `URLClassPath` resolves
 * them for a jar on disk. The value splits on the whitespace `StringTokenizer` splits on. Each
 * entry is a URL relative to [jar]. An entry whose protocol is not `file` (compared ignoring
 * case) is dropped, and so is a jar entry whose host is neither empty nor `localhost`, as the
 * system loader drops both. A directory entry, one that ends in `/`, is read whatever its host. An entry that is not a valid URL is dropped too; the system loader then skips the whole
 * jar, so this reads more than it loads, never less. A path is read from the URL's file part with
 * its `%` escapes decoded, so a space arrives as `%20`, and characters `java.net.URI` refuses,
 * such as `[` and `]`, are kept.
 *
 * Shared by the dependency listing and the class listing, so both parse `Class-Path` the same way.
 */
internal fun classPathEntries(
    jar: File,
    manifest: Manifest?,
): List<ClassPathEntry> {
    val value = manifest?.mainAttributes?.getValue("Class-Path") ?: return emptyList()
    val base = jar.toURI().toURL()
    val tokens = StringTokenizer(value)
    val entries = mutableListOf<ClassPathEntry>()
    while (tokens.hasMoreTokens()) {
        val resolved =
            try {
                URL(base, tokens.nextToken())
            } catch (_: MalformedURLException) {
                continue
            }
        if (!"file".equals(resolved.protocol, ignoreCase = true)) continue
        val host = resolved.host.orEmpty()
        // The JDK reads a directory URL's local path whatever its host, and a jar URL only when
        // the host is empty or localhost.
        if (!resolved.file.endsWith("/") && host.isNotEmpty() && !host.equals("localhost", ignoreCase = true)) continue
        entries += ClassPathEntry(File(percentDecoded(resolved.file)), isDirectory = resolved.file.endsWith("/"))
    }
    return entries
}

/** [path] with each `%XX` escape decoded as UTF-8 bytes, and every other character kept. */
private fun percentDecoded(path: String): String {
    if ('%' !in path) return path
    val raw = path.toByteArray(Charsets.UTF_8)
    val out = ByteArrayOutputStream(raw.size)
    var index = 0
    while (index < raw.size) {
        val byte = raw[index]
        if (byte == '%'.code.toByte() && index + 2 < raw.size) {
            val code = String(raw, index + 1, 2, Charsets.US_ASCII).toIntOrNull(16)
            if (code != null) {
                out.write(code)
                index += 3
                continue
            }
        }
        out.write(byte.toInt())
        index++
    }
    return out.toString(Charsets.UTF_8)
}
