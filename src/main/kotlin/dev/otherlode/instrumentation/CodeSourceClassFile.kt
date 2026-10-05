package dev.otherlode.instrumentation

import java.net.URI
import java.net.URL
import java.security.ProtectionDomain

/**
 * Reads a class's own class file from the code source of its [ProtectionDomain]: the location plus
 * the class's path, which names the copy the JVM defined it from.
 *
 * Asking the class loader for the same file costs more in a loader with many jars: Spring Boot's
 * nested-jar loader probes each nested jar in turn, building a URL and a connection for every probe.
 * A location this reads says where the class came from, so it takes one open.
 *
 * Three location shapes are read, and anything else is left to the loader:
 *
 * - a directory, `file:/app/classes/`, with the class path resolved against it;
 * - a jar file, `file:/app/x.jar`, read as `jar:file:/app/x.jar!/com/acme/Foo.class`;
 * - a `jar:` location ending in `!/` over a `file:` or `nested:` URL, Spring Boot 2's
 *   `jar:file:/app.jar!/BOOT-INF/lib/x.jar!/` and Boot 3.2's `jar:nested:/app.jar/!BOOT-INF/lib/x.jar!/`,
 *   with the class path resolved against it by the URL's own handler.
 *
 * A `jar:` location over any other protocol is not read, so a class defined from a remote jar never
 * makes this agent open a network connection.
 */
internal object CodeSourceClassFile {
    private const val JAR_PREFIX = "jar:"
    private const val JAR_END = "!/"

    /**
     * The URL of the class file named by [internalName] (`com/acme/Foo`) under [location], or null
     * when [location] is null or has a shape this does not read.
     */
    fun urlOf(
        location: URL?,
        internalName: String,
    ): URL? {
        location ?: return null
        return try {
            val relative = URI(null, null, "$internalName.class", null).rawPath
            val text = location.toExternalForm()
            when {
                location.protocol.equals("file", ignoreCase = true) -> {
                    if (text.endsWith("/")) URL(location, relative) else URL(URL(JAR_PREFIX + text + JAR_END), relative)
                }

                location.protocol.equals("jar", ignoreCase = true) && isReadableJar(text) -> {
                    URL(location, relative)
                }

                else -> {
                    null
                }
            }
        } catch (_: java.net.MalformedURLException) {
            null
        } catch (_: java.net.URISyntaxException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun isReadableJar(text: String): Boolean =
        text.endsWith(JAR_END) &&
            (
                text.startsWith(JAR_PREFIX + "file:", ignoreCase = true) ||
                    text.startsWith(JAR_PREFIX + "nested:", ignoreCase = true)
            )

    /**
     * The bytes of the class file for [internalName] at the code source of [protectionDomain], or null
     * when there is no domain, no code source or location, a shape [urlOf] does not read, no entry at
     * the location, or any failure in reading it. A null answer sends the caller to the class loader.
     */
    fun read(
        protectionDomain: ProtectionDomain?,
        internalName: String,
    ): ByteArray? {
        val url =
            try {
                urlOf(protectionDomain?.codeSource?.location, internalName)
            } catch (_: SecurityException) {
                null
            } ?: return null
        return try {
            url.openStream().use { it.readBytes() }
        } catch (_: Exception) {
            null
        }
    }
}
