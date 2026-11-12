package dev.otherlode.instrumentation

import dev.otherlode.bootLogger
import java.net.URI
import java.net.URL
import java.security.ProtectionDomain
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Reads a class's own class file from the code source of its [ProtectionDomain]. It reads a
 * directory, a jar file, and a `jar:` location ending in `!/` over a `file:` or `nested:` URL. It
 * leaves any other location to the class loader, and opens no network connection.
 *
 * A directory is read at once. Each jar shape is warmed up first, keyed by [warmKey]: the first
 * read hands a read to [warmer], which runs it outside any transform, and answers null unless that
 * warm-up has already finished. Reads while the warm-up runs answer null.
 */
class CodeSourceClassFile internal constructor(
    private val warmer: Executor,
) {
    /** An instance that runs each warm-up on its own short-lived daemon thread. */
    constructor() : this(DaemonWarmer)

    internal enum class State { NOT_WARMED, WARMING, WARMED, FAILED }

    private val states = ConcurrentHashMap<String, AtomicReference<State>>()
    private val attempts = ConcurrentHashMap<String, AtomicInteger>()

    private val jarTemplate = AtomicReference<URL?>()

    init {
        Class.forName(WarmUp::class.java.name)
        Class.forName(DaemonWarmer::class.java.name)
        State.entries.size
    }

    internal fun stateOf(key: String): State = states[key]?.get() ?: State.NOT_WARMED

    /**
     * The bytes of the class file for [internalName] at the code source of [protectionDomain], or null
     * when this cannot read them.
     */
    fun read(
        protectionDomain: ProtectionDomain?,
        internalName: String,
    ): ByteArray? {
        val location =
            try {
                protectionDomain?.codeSource?.location
            } catch (_: SecurityException) {
                null
            } ?: return null
        val key = warmKey(location)
        if (key == null) {
            if (!isDirectory(location)) return null
        } else if (!isWarmed(key, location, internalName)) {
            return null
        }
        return try {
            val url = urlOf(location, internalName, jarTemplate.get()) ?: return null
            open(url)
        } catch (_: Exception) {
            null
        } catch (_: LinkageError) {
            null
        }
    }

    // Reads the URL's fields only, so no URL handler runs on a transforming thread.
    private fun warmKey(location: URL): String? {
        if (location.ref != null) return null
        val protocol = location.protocol
        val file = location.file ?: return null
        if (protocol.equals(FILE, ignoreCase = true)) return if (file.endsWith("/")) null else JAR_FILE_KEY
        if (!protocol.equals(JAR, ignoreCase = true) || !file.endsWith(JAR_END)) return null
        if (file.regionMatches(0, "file:", 0, 5, ignoreCase = true)) return JAR_FILE_LOCATION_KEY
        if (file.regionMatches(0, "nested:", 0, 7, ignoreCase = true)) return JAR_NESTED_KEY
        return null
    }

    private fun isDirectory(location: URL): Boolean =
        location.ref == null && location.protocol.equals(FILE, ignoreCase = true) && location.file?.endsWith("/") == true

    private fun isWarmed(
        key: String,
        location: URL,
        internalName: String,
    ): Boolean {
        var reference = states[key]
        if (reference == null) {
            states.putIfAbsent(key, AtomicReference(State.NOT_WARMED))
            reference = states.getValue(key)
        }
        val state = reference.get()
        if (state == State.WARMED) return true
        if (state != State.NOT_WARMED || !reference.compareAndSet(State.NOT_WARMED, State.WARMING)) return false
        var counter = attempts[key]
        if (counter == null) {
            attempts.putIfAbsent(key, AtomicInteger())
            counter = attempts.getValue(key)
        }
        val template = if (key == JAR_FILE_KEY) jarTemplate else null
        try {
            warmer.execute(WarmUp(location, internalName, reference, counter, template))
        } catch (_: Throwable) {
            missed(reference, counter, log = false)
        }
        return reference.get() == State.WARMED
    }

    private class WarmUp(
        private val location: URL,
        private val internalName: String,
        private val state: AtomicReference<State>,
        private val attempts: AtomicInteger,
        private val template: AtomicReference<URL?>?,
    ) : Runnable {
        override fun run() {
            var warmed = false
            try {
                val base = if (template != null) jarBaseOf(location, null) else null
                val url = urlOf(location, internalName, base)
                warmed = url != null && open(url) != null
                if (warmed && base != null) template?.compareAndSet(null, base)
            } catch (_: Throwable) {
                warmed = false
            } finally {
                if (warmed) state.set(State.WARMED) else missed(state, attempts, log = true)
            }
        }
    }

    private object DaemonWarmer : Executor {
        override fun execute(command: Runnable) {
            // inheritThreadLocals is false: copying them runs each one's childValue, which is application code.
            val thread = Thread(null, command, WARM_UP_THREAD, 0, false)
            thread.isDaemon = true
            thread.start()
        }
    }

    companion object {
        internal const val MAX_WARM_UP_ATTEMPTS = 16

        private const val FILE = "file"
        private const val JAR = "jar"
        private const val JAR_PREFIX = "jar:"
        private const val JAR_END = "!/"
        private const val JAR_FILE_KEY = "jar:file"
        private const val JAR_FILE_LOCATION_KEY = "jar:file-location"
        private const val JAR_NESTED_KEY = "jar:nested"
        private const val WARM_UP_THREAD = "otherlode-code-source-warmup"

        // Pass log = false on a transforming thread, where a logging backend may load classes.
        private fun missed(
            state: AtomicReference<State>,
            attempts: AtomicInteger,
            log: Boolean,
        ) {
            val failed = attempts.incrementAndGet() >= MAX_WARM_UP_ATTEMPTS
            state.set(if (failed) State.FAILED else State.NOT_WARMED)
            if (failed && log) {
                bootLogger(CodeSourceClassFile::class.java.name).log(
                    System.Logger.Level.DEBUG,
                    "otherlode: code-source warm-up reads kept finding nothing; this location shape is read through the loader",
                )
            }
        }

        // URL(template, spec) inherits the template's handler, so no handler lookup runs on a transforming thread.
        // WarmUp stores the template before it marks the key warmed.
        private fun jarBaseOf(
            location: URL,
            template: URL?,
        ): URL {
            val spec = JAR_PREFIX + location.toExternalForm() + JAR_END
            return if (template != null) URL(template, spec) else URL(spec)
        }

        /**
         * The URL of the class file named by [internalName] (`com/acme/Foo`) under [location], or null
         * when [location] is null or has a shape this does not read. A jar file's URL is built with
         * [jarTemplate]'s handler when one is given.
         */
        fun urlOf(
            location: URL?,
            internalName: String,
            jarTemplate: URL? = null,
        ): URL? {
            location ?: return null
            return try {
                val relative = URI(null, null, "$internalName.class", null).rawPath
                val text = location.toExternalForm()
                when {
                    location.protocol.equals(FILE, ignoreCase = true) -> {
                        if (text.endsWith("/")) URL(location, relative) else URL(jarBaseOf(location, jarTemplate), relative)
                    }

                    location.protocol.equals(JAR, ignoreCase = true) && isReadableJar(text) -> {
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

        private fun open(url: URL): ByteArray? =
            try {
                url.openStream().use { it.readBytes() }
            } catch (_: Exception) {
                null
            }
    }
}
