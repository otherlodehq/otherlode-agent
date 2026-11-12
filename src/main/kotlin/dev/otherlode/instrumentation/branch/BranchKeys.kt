package dev.otherlode.instrumentation.branch

import java.security.MessageDigest

/**
 * Derives the branch key of each kept branch outcome, and the site key of each kept site, from the
 * site's condition fingerprint.
 *
 * Two tracked sites in one method collide, and get no key, when they share a
 * [BranchSite.conditionFingerprint] and [BranchSite.inlinedFromClassName]. Dropped sites count
 * toward a collision. A site with a null fingerprint never gets a key and never takes part in a
 * collision.
 */
object BranchKeys {
    private const val DERIVATION_TAG = "v1"

    private const val SITE_DERIVATION_TAG = "site-v1"

    private const val SEPARATOR = "\u0000"

    private const val HEX_CHARS = 32

    private val HEX_DIGITS = "0123456789abcdef".toCharArray()

    private data class CollisionIdentity(
        val methodName: String,
        val methodDescriptor: String,
        val conditionFingerprint: String,
        val inlinedFromClassName: String?,
    )

    /**
     * Computes the branch key of every kept outcome of [sites], one class's analysed branch
     * sites, keyed by `(siteIndex, outcome offset)`. [className] is the class's own name, dotted.
     * [reportedName] gives the method name a key digests from the method's real name and
     * descriptor.
     */
    fun compute(
        sites: List<BranchSite>,
        className: String,
        reportedName: (name: String, descriptor: String) -> String = { name, _ -> name },
    ): Map<Pair<Int, Int>, String> {
        val keys = mutableMapOf<Pair<Int, Int>, String>()
        val classBytes = utf8(className)
        for (nameable in nameableSites(sites, reportedName)) {
            val input = DigestInput(classBytes, nameable.site, nameable.fingerprint, reportedName)
            nameable.outcomeTokens.forEachIndexed { offset, outcomeToken ->
                if (outcomeToken == null) return@forEachIndexed
                keys[nameable.site.siteIndex to offset] = digest(DERIVATION_TAG_BYTES, input, outcomeToken)
            }
        }
        return keys
    }

    /**
     * Computes the site key of every kept site of [sites], keyed by `siteIndex`. A site is missing
     * from the result exactly when [compute] gives none of its outcomes a key. [className] and
     * [reportedName] are as for [compute].
     */
    fun computeSiteKeys(
        sites: List<BranchSite>,
        className: String,
        reportedName: (name: String, descriptor: String) -> String = { name, _ -> name },
    ): Map<Int, String> {
        val classBytes = utf8(className)
        return nameableSites(sites, reportedName).associate { nameable ->
            nameable.site.siteIndex to
                digest(SITE_DERIVATION_TAG_BYTES, DigestInput(classBytes, nameable.site, nameable.fingerprint, reportedName), null)
        }
    }

    private class NameableSite(
        val site: BranchSite,
        val fingerprint: String,
        val outcomeTokens: List<String?>,
    )

    private fun nameableSites(
        sites: List<BranchSite>,
        reportedName: (name: String, descriptor: String) -> String,
    ): List<NameableSite> {
        val collisionCounts = mutableMapOf<CollisionIdentity, Int>()
        for (site in sites) {
            val fingerprint = site.conditionFingerprint ?: continue
            val identity =
                CollisionIdentity(
                    reportedName(site.methodName, site.methodDescriptor),
                    site.methodDescriptor,
                    fingerprint,
                    site.inlinedFromClassName,
                )
            collisionCounts[identity] = (collisionCounts[identity] ?: 0) + 1
        }

        val nameable = mutableListOf<NameableSite>()
        for (site in sites) {
            if (site.dropReason != null) continue
            val fingerprint = site.conditionFingerprint ?: continue
            val identity =
                CollisionIdentity(
                    reportedName(site.methodName, site.methodDescriptor),
                    site.methodDescriptor,
                    fingerprint,
                    site.inlinedFromClassName,
                )
            if (collisionCounts.getValue(identity) > 1) continue
            val outcomeTokens = outcomeTokensOf(site) ?: continue
            nameable += NameableSite(site, fingerprint, outcomeTokens)
        }
        return nameable
    }

    private fun outcomeTokensOf(site: BranchSite): List<String?>? {
        val labels = site.caseLabels
        if (labels != null) {
            if (labels.size != site.outcomeCount - 1) return null
            val tokens = labels.map { "label:${it.kind}:${it.text}" }
            val counts = tokens.groupingBy { it }.eachCount()
            val caseTokens = tokens.map { token -> token.takeIf { counts.getValue(it) == 1 } }
            if (site.throwingDefault && caseTokens.all { it == null }) return null
            return caseTokens + "default"
        }
        val caseKeys = site.caseKeys
        if (caseKeys == null) {
            if (site.outcomeCount != 2) return null
            return listOf("taken", "fallthrough")
        }
        if (caseKeys.size != site.outcomeCount - 1) return null
        return caseKeys.map { "case:$it" } + "default"
    }

    private fun utf8(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

    private val DERIVATION_TAG_BYTES = utf8(DERIVATION_TAG)
    private val SITE_DERIVATION_TAG_BYTES = utf8(SITE_DERIVATION_TAG)
    private val SEPARATOR_BYTES = utf8(SEPARATOR)

    private val sha256: ThreadLocal<MessageDigest> = ThreadLocal.withInitial { MessageDigest.getInstance("SHA-256") }

    private class DigestInput(
        val className: ByteArray,
        site: BranchSite,
        fingerprint: String,
        reportedName: (name: String, descriptor: String) -> String,
    ) {
        val methodName = utf8(reportedName(site.methodName, site.methodDescriptor))
        val methodDescriptor = utf8(site.methodDescriptor)
        val origin = utf8(site.inlinedFromClassName ?: "")
        val fingerprint = utf8(fingerprint)
    }

    private fun digest(
        tag: ByteArray,
        input: DigestInput,
        outcomeToken: String?,
    ): String {
        val digest = sha256.get()
        digest.update(tag)
        digest.update(SEPARATOR_BYTES)
        digest.update(input.className)
        digest.update(SEPARATOR_BYTES)
        digest.update(input.methodName)
        digest.update(SEPARATOR_BYTES)
        digest.update(input.methodDescriptor)
        digest.update(SEPARATOR_BYTES)
        digest.update(input.origin)
        digest.update(SEPARATOR_BYTES)
        digest.update(input.fingerprint)
        if (outcomeToken != null) {
            digest.update(SEPARATOR_BYTES)
            digest.update(utf8(outcomeToken))
        }
        val hash = digest.digest()
        val hex = CharArray(HEX_CHARS)
        for (i in 0 until HEX_CHARS / 2) {
            val byte = hash[i].toInt()
            hex[2 * i] = HEX_DIGITS[(byte shr 4) and 0xF]
            hex[2 * i + 1] = HEX_DIGITS[byte and 0xF]
        }
        return String(hex)
    }
}
