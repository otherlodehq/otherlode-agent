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

internal fun bootLibPrefixes(attributes: Attributes): List<String> {
    val isWar =
        attributes.getValue(BOOT_LIB)?.trim()?.startsWith(WAR_PREFIX) == true ||
            attributes.getValue("Main-Class")?.trim()?.endsWith(WAR_LAUNCHER) == true
    return if (isWar) WAR_LIB_PREFIXES else JAR_LIB_PREFIXES
}

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

internal data class ClassPathEntry(
    val file: File,
    val isDirectory: Boolean,
)

internal fun classPathEntries(
    jar: File,
    manifest: Manifest?,
): List<ClassPathEntry> {
    val value = manifest?.mainAttributes?.getValue("Class-Path") ?: return emptyList()
    val base = jar.toURI().toURL()
    val tokens = StringTokenizer(value)
    val entries = mutableListOf<ClassPathEntry>()
    while (tokens.hasMoreTokens()) {
        val resolved = URL(base, tokens.nextToken())
        if (!"file".equals(resolved.protocol, ignoreCase = true)) continue
        val host = resolved.host.orEmpty()
        if (!resolved.file.endsWith("/") && host.isNotEmpty() && !host.equals("localhost", ignoreCase = true)) continue
        entries += ClassPathEntry(File(percentDecoded(resolved.file)), isDirectory = resolved.file.endsWith("/"))
    }
    return entries
}

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
