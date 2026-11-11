package dev.otherlode.instrumentation.branch

import dev.otherlode.agentLogger
import java.lang.System.Logger.Level

internal object ScalaReleases {
    private const val RESOURCE = "/dev/otherlode/scala3-read-releases.txt"
    private const val TOOLING_PREFIX = "Scala "
    private val MAGIC = byteArrayOf(0x5C, 0xA1.toByte(), 0xAB.toByte(), 0x1F)

    const val HEADER_BYTES = 512

    private val log by agentLogger(ScalaReleases::class.java.name)

    val read: Set<String> by lazy { loadReadReleases() }

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
