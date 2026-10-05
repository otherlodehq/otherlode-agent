package dev.otherlode.instrumentation

import java.net.URI
import java.net.URL
import java.security.ProtectionDomain
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

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
 *
 * A directory is read at once: the JVM never lets an application replace the `file` handler it looks
 * up. A jar URL is opened by whichever `jar` handler the JVM's lookup finds (an application may
 * install one through `java.protocol.handler.pkgs`, as Spring Boot does, or a
 * `URLStreamHandlerFactory`) or by the handler the location URL itself carries, and building or
 * opening that URL the first time loads the handler's classes. The JVM offers a class
 * loaded from inside a transform to no transformer (ADR 0027), so such classes would go unwoven. Each
 * jar shape is therefore warmed up first, keyed by [warmKey]: the first read hands the same read to
 * [warmer], which builds the URL and opens it on another thread, outside any transform, and answers
 * null, so the caller uses its loader, unless that warm-up has already finished, in which case the
 * handler's classes are loaded and the read is answered from the code source. Reads while the
 * warm-up runs answer null. Once a warm-up read
 * succeeds the key is read from the code source; a warm-up read that finds nothing may only mean that
 * one class (a generated one defined under the application's domain, say) is missing at its location,
 * so the key goes back to unwarmed and a later class tries again, until [MAX_WARM_UP_ATTEMPTS] misses
 * leave the key on the loader for the life of the instance. A jar file's URL is built afterwards
 * from the one the warm-up built, so it keeps that handler even if an application registers a
 * handler factory later, and no handler lookup runs in a transform.
 *
 * A class read while its key is not yet warm is analysed from the copy its loader returns, which for
 * a class shadowed by another jar's copy is not the one the JVM defined.
 *
 * One instance serves the agent. Before a key is warm the transforming thread only reads fields of
 * the location URL; it builds no URL and calls no handler.
 */
class CodeSourceClassFile internal constructor(
    private val warmer: Executor,
) {
    /** An instance that runs each warm-up on its own short-lived daemon thread. */
    constructor() : this(DaemonWarmer)

    /** Where one jar shape stands. */
    internal enum class State { NOT_WARMED, WARMING, WARMED, FAILED }

    private val states = ConcurrentHashMap<String, AtomicReference<State>>()
    private val attempts = ConcurrentHashMap<String, AtomicInteger>()

    /** A `jar:` URL the warm-up of [JAR_FILE_KEY] built, whose handler every later jar file URL inherits. */
    private val jarTemplate = AtomicReference<URL?>()

    init {
        Class.forName(WarmUp::class.java.name)
        Class.forName(DaemonWarmer::class.java.name)
        State.entries.size
    }

    /** The state of [key], such as `jar:nested`; for tests. */
    internal fun stateOf(key: String): State = states[key]?.get() ?: State.NOT_WARMED

    /**
     * The bytes of the class file for [internalName] at the code source of [protectionDomain], or null
     * when there is no domain, no code source or location, a shape this does not read, a key not yet
     * warm, no entry at the location, or any failure in reading it. A null answer sends the caller to
     * the class loader.
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

    /**
     * The key a jar shape is warmed under, or null for a directory, which needs no warm-up, and for a
     * location this does not read. Read from the URL's fields only, so no handler method runs:
     * [JAR_FILE_KEY] for a jar file, whose URL the `jar` handler the JVM looks up builds, and
     * [JAR_FILE_LOCATION_KEY] or [JAR_NESTED_KEY] for a `jar:` location, which carries its own handler.
     * A location with a fragment is not read.
     */
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

    /**
     * One warm-up read, on a thread outside any transform. Whatever it throws, the key leaves
     * [State.WARMING]: a success warms it, anything else counts as a miss. For a jar file it also
     * keeps the `jar:` URL it built in [template], before the key is marked warm.
     */
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

    /**
     * Runs each warm-up on its own daemon thread, created without copying the caller's inheritable
     * thread-locals: copying runs each one's `childValue`, which is application code.
     */
    private object DaemonWarmer : Executor {
        override fun execute(command: Runnable) {
            val thread = Thread(null, command, WARM_UP_THREAD, 0, false)
            thread.isDaemon = true
            thread.start()
        }
    }

    companion object {
        /** Warm-up reads of one key that may find nothing before the key is left on the loader. */
        internal const val MAX_WARM_UP_ATTEMPTS = 16

        private const val FILE = "file"
        private const val JAR = "jar"
        private const val JAR_PREFIX = "jar:"
        private const val JAR_END = "!/"
        private const val JAR_FILE_KEY = "jar:file"
        private const val JAR_FILE_LOCATION_KEY = "jar:file-location"
        private const val JAR_NESTED_KEY = "jar:nested"
        private const val WARM_UP_THREAD = "otherlode-code-source-warmup"

        /** Counts a miss; [log] is false on a transforming thread, where a logging backend may load classes. */
        private fun missed(
            state: AtomicReference<State>,
            attempts: AtomicInteger,
            log: Boolean,
        ) {
            val failed = attempts.incrementAndGet() >= MAX_WARM_UP_ATTEMPTS
            state.set(if (failed) State.FAILED else State.NOT_WARMED)
            if (failed && log) {
                System.getLogger(CodeSourceClassFile::class.java.name).log(
                    System.Logger.Level.DEBUG,
                    "otherlode: code-source warm-up reads kept finding nothing; this location shape is read through the loader",
                )
            }
        }

        /**
         * The `jar:` URL of the jar file at [location], built with [template]'s handler when one is
         * given (a URL built against a context of the same protocol inherits the context's handler,
         * so no lookup runs) and through the JVM's handler lookup otherwise.
         */
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
         * [jarTemplate]'s handler when one is given; see [jarBaseOf].
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
