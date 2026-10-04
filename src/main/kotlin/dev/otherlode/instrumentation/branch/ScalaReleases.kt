package dev.otherlode.instrumentation.branch

import java.lang.System.Logger.Level

/**
 * The Scala 3 compiler release a class was compiled by, and whether the agent has read it.
 *
 * A Scala 3 top-level class has a `.tasty` file beside its class file, and the file's header names
 * the compiler: the magic `5CA1AB1F`, the major, minor and experimental version as variable-length
 * naturals, the length of the tooling string and the string itself (`Scala 3.9.0`), then a 16-byte
 * UUID. A nested, module, local or anonymous class has no file of its own and takes its enclosing
 * top-level class's, found by cutting its name at each `$` in turn.
 */
internal object ScalaReleases {
    private const val RESOURCE = "/dev/otherlode/scala3-read-releases.txt"
    private const val TOOLING_PREFIX = "Scala "
    private val MAGIC = byteArrayOf(0x5C, 0xA1.toByte(), 0xAB.toByte(), 0x1F)

    /** The bytes of a `.tasty` file the release reader needs, for a resource lookup that reads only a header. */
    const val HEADER_BYTES = 512

    private val log = System.getLogger(ScalaReleases::class.java.name)

    /** The releases listed in `scala3-read-releases.txt`, which is empty (and logged once) when the resource cannot be read. */
    val read: Set<String> by lazy { loadReadReleases() }

    /** Whether the agent's shape rules were checked against [release]. */
    fun isRead(release: String): Boolean = release in read

    private fun loadReadReleases(): Set<String> =
        try {
            ScalaReleases::class.java.getResourceAsStream(RESOURCE)?.use { stream ->
                stream
                    .readBytes()
                    .toString(Charsets.UTF_8)
                    .lineSequence()
                    .map { it.substringBefore('#').trim() }
                    .filter { it.isNotEmpty() }
                    .toSet()
            } ?: emptySet<String>().also { log.log(Level.WARNING, "otherlode: $RESOURCE is missing; no Scala 3 release counts as read") }
        } catch (e: Exception) {
            log.log(Level.WARNING, "otherlode: could not read $RESOURCE; no Scala 3 release counts as read", e)
            emptySet()
        }

    /**
     * The compiler release [tasty] names, such as `3.9.0`, or null when it is not a TASTy header.
     * The tooling string is `Scala <release>` for the compiler; any other string is returned whole.
     */
    fun releaseOf(tasty: ByteArray): String? {
        if (tasty.size < MAGIC.size || !MAGIC.indices.all { tasty[it] == MAGIC[it] }) return null
        var at = MAGIC.size

        fun nat(): Int? {
            var value = 0
            while (at < tasty.size) {
                val b = tasty[at++].toInt()
                value = (value shl 7) or (b and 0x7f)
                if (b and 0x80 != 0) return value
                if (value > Int.MAX_VALUE shr 8) return null
            }
            return null
        }
        repeat(3) { nat() ?: return null }
        val length = nat() ?: return null
        if (length <= 0 || at + length > tasty.size) return null
        val tooling = String(tasty, at, length, Charsets.UTF_8).trim()
        return tooling.removePrefix(TOOLING_PREFIX).takeIf { it.isNotEmpty() }
    }

    /**
     * The release that compiled the class [internalName], from its own `.tasty` or, failing that,
     * the one of each enclosing class named by cutting at a `$` in the simple name, nearest first.
     * Null when none of them exists or none has a readable header. [resources] returns the leading
     * bytes of a resource by its path. [cache], when given, remembers every name tried, so a family
     * of nested classes reads its top-level file once.
     */
    fun releaseOf(
        internalName: String,
        resources: (path: String) -> ByteArray?,
        cache: BranchSiteAnalyzer.CrossClassTableCache? = null,
    ): String? {
        var candidate: String? = internalName
        while (candidate != null) {
            val release =
                if (cache != null) {
                    cache.getOrReadRelease(candidate) { read(candidate!!, resources) }
                } else {
                    read(candidate, resources)
                }
            if (release != null) return release
            candidate = enclosingCandidate(candidate)
        }
        return null
    }

    private fun read(
        internalName: String,
        resources: (String) -> ByteArray?,
    ): String? =
        try {
            resources("$internalName.tasty")?.let(::releaseOf)
        } catch (_: Exception) {
            null
        }

    private fun enclosingCandidate(internalName: String): String? {
        val cut = internalName.lastIndexOf('$')
        return if (cut > internalName.lastIndexOf('/') + 1) internalName.substring(0, cut) else null
    }
}
