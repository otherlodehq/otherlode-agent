package dev.otherlode.dependencies

import dev.otherlode.instrumentation.staticscan.isClassEntry
import dev.otherlode.instrumentation.staticscan.versionedClassPath
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Properties
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.Manifest
import java.util.zip.DataFormatException
import java.util.zip.Inflater
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/**
 * Reads the [JarContents] of a jar nested inside another, the way Spring Boot reads it: from the
 * nested jar's central directory, so the library classpath is never inflated.
 *
 * A nested jar the outer jar stores uncompressed is read by random access: the end of central
 * directory record, the ZIP64 records when present, the central directory, and then only the
 * local entries whose data is needed, the `pom.properties` files and the manifest. A nested jar
 * the outer jar compresses is streamed with [ZipInputStream], since random access into it would
 * inflate it anyway.
 *
 * A ZIP64 end record found through the locator before the end record is used whenever its fields
 * agree with the end record's, saturated or not, as the JDK's `ZipFile` decides. Fewer bytes than a
 * record after the last central record are ignored, since Spring Boot reads only the records the end
 * record counts.
 *
 * Every stream opened is closed before the read returns, and only the central directory and the
 * needed entries are held in memory, never the jar.
 */
internal object NestedJarReader {
    private const val END_SIGNATURE = 0x06054b50
    private const val END_SIZE = 22
    private const val ZIP64_LOCATOR_SIGNATURE = 0x07064b50
    private const val ZIP64_LOCATOR_SIZE = 20
    private const val ZIP64_RECORD_SIGNATURE = 0x06064b50
    private const val ZIP64_RECORD_MIN_SIZE = 56
    private const val CENTRAL_SIGNATURE = 0x02014b50
    private const val CENTRAL_SIZE = 46
    private const val LOCAL_SIGNATURE = 0x04034b50
    private const val LOCAL_SIZE = 30
    private const val MAX_COMMENT = 65535
    private const val ZIP64_EXTRA_ID = 0x0001
    private const val ENCRYPTED_FLAG = 0x1
    private const val SATURATED_16 = 0xFFFF
    private const val SATURATED_32 = 0xFFFFFFFFL

    /**
     * The contents of [entry], a nested jar of [outer]. Throws for a jar that cannot be read: a
     * [ZipException] naming what was wrong with a malformed stored jar, what parsing its manifest or a
     * `pom.properties` throws, or what [ZipInputStream] throws for a compressed one.
     */
    fun read(
        outer: JarFile,
        entry: JarEntry,
    ): JarContents {
        if (entry.method != ZipEntry.STORED) return ZipInputStream(outer.getInputStream(entry)).use(JarContents::of)
        val source = Source(outer, entry)
        return source.contents()
    }

    private class Zip64End(
        val position: Long,
        val total: Long,
        val centralSize: Long,
        val centralOffset: Long,
    )

    private class CentralEntry(
        val name: String,
        val method: Int,
        val compressedSize: Long,
        val localOffset: Long,
    )

    private class Source(
        val outer: JarFile,
        val entry: JarEntry,
    ) {
        val size: Long = entry.size
        val label: String = "${outer.name}!/${entry.name}"

        fun fail(why: String): Nothing = throw ZipException("$label: $why")

        fun read(
            position: Long,
            length: Int,
        ): ByteArray {
            if (position < 0 || length < 0 ||
                position + length > size
            ) {
                fail("a read of $length bytes at $position runs past the $size-byte entry")
            }
            val out = ByteArray(length)
            outer.getInputStream(entry).use { stream ->
                skipFully(stream, position)
                var filled = 0
                while (filled < length) {
                    val n = stream.read(out, filled, length - filled)
                    if (n < 0) fail("the entry ends at ${position + filled}, before byte ${position + length}")
                    filled += n
                }
            }
            return out
        }

        private fun skipFully(
            stream: InputStream,
            count: Long,
        ) {
            var left = count
            while (left > 0) {
                val skipped = stream.skip(left)
                if (skipped > 0) {
                    left -= skipped
                } else if (stream.read() < 0) {
                    fail("the entry ends before byte $count")
                } else {
                    left--
                }
            }
        }

        fun contents(): JarContents {
            if (size < END_SIZE) fail("holds no zip entries")
            val tailLength = minOf(size, (END_SIZE + MAX_COMMENT).toLong()).toInt()
            val tailStart = size - tailLength
            val tail = le(read(tailStart, tailLength))
            val endAt = findEnd(tail) ?: fail("holds no zip entries")
            var total = tail.getShort(endAt + 10).toLong() and 0xFFFF
            var centralSize = tail.getInt(endAt + 12).toLong() and SATURATED_32
            var centralOffset = tail.getInt(endAt + 16).toLong() and SATURATED_32
            var endPosition = tailStart + endAt
            val zip64 = zip64End(endPosition, total, centralSize, centralOffset)
            if (zip64 != null) {
                total = zip64.total
                centralSize = zip64.centralSize
                centralOffset = zip64.centralOffset
                endPosition = zip64.position
            } else if (centralSize == SATURATED_32 || centralOffset == SATURATED_32) {
                fail("the end record is saturated but no ZIP64 end record agrees with it")
            }
            if (total == 0L) fail("holds no zip entries")
            if (centralSize < 0 || centralOffset < 0 || centralSize > minOf(size, Int.MAX_VALUE.toLong()) || total < 0) {
                fail("the central directory is out of range")
            }
            val start = endPosition - centralSize - centralOffset
            if (start < 0) fail("the central directory ends before the zip starts")
            if (total > centralSize / CENTRAL_SIZE) fail("the central directory is too short for $total entries")
            if (start + centralOffset + centralSize > size) fail("the central directory runs past the entry")
            val central = le(read(start + centralOffset, centralSize.toInt()))
            val entries = parseCentral(central, total)
            return assemble(entries, start)
        }

        private fun le(bytes: ByteArray): ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        private fun findEnd(tail: ByteBuffer): Int? {
            var at = tail.capacity() - END_SIZE
            while (at >= 0) {
                if (tail.getInt(at) == END_SIGNATURE) {
                    val comment = tail.getShort(at + 20).toInt() and 0xFFFF
                    if (at + END_SIZE + comment <= tail.capacity()) return at
                }
                at--
            }
            return null
        }

        /**
         * The ZIP64 end record that the locator just before [endPosition] points to, or null when no
         * locator precedes the end record, no record answers it, or a field of the record disagrees
         * with an unsaturated field of the end record, as the JDK's `ZipFile` decides. The record is
         * looked for at the locator's offset, where it must end by the locator, as the JDK and Spring
         * Boot look for it. In a zip with a prefix that offset misses, so it is then looked for just
         * before the locator, where its own size field must place it.
         */
        private fun zip64End(
            endPosition: Long,
            total: Long,
            centralSize: Long,
            centralOffset: Long,
        ): Zip64End? {
            if (endPosition < ZIP64_LOCATOR_SIZE) return null
            val locatorPosition = endPosition - ZIP64_LOCATOR_SIZE
            val locator = le(read(locatorPosition, ZIP64_LOCATOR_SIZE))
            if (locator.getInt(0) != ZIP64_LOCATOR_SIGNATURE) return null
            val atOffset = locator.getLong(8)
            val beforeLocator = locatorPosition - ZIP64_RECORD_MIN_SIZE
            for (candidate in longArrayOf(atOffset, beforeLocator)) {
                if (candidate < 0 || candidate + ZIP64_RECORD_MIN_SIZE > locatorPosition) continue
                val record = le(read(candidate, ZIP64_RECORD_MIN_SIZE))
                if (record.getInt(0) != ZIP64_RECORD_SIGNATURE) continue
                if (candidate != atOffset && locatorPosition - (12 + record.getLong(4)) != candidate) continue
                val end = Zip64End(candidate, record.getLong(32), record.getLong(40), record.getLong(48))
                val agrees =
                    (total == SATURATED_16.toLong() || end.total == total) &&
                        (centralSize == SATURATED_32 || end.centralSize == centralSize) &&
                        (centralOffset == SATURATED_32 || end.centralOffset == centralOffset)
                return if (agrees) end else null
            }
            return null
        }

        private fun parseCentral(
            central: ByteBuffer,
            total: Long,
        ): List<CentralEntry> {
            val entries = ArrayList<CentralEntry>(total.toInt().coerceAtMost(central.capacity() / CENTRAL_SIZE))
            var at = 0
            while (central.capacity() - at >= CENTRAL_SIZE) {
                if (central.getInt(at) != CENTRAL_SIGNATURE) {
                    fail("a central directory record at $at has a bad signature")
                }
                val flags = central.getShort(at + 8).toInt() and 0xFFFF
                val method = central.getShort(at + 10).toInt() and 0xFFFF
                var compressedSize = central.getInt(at + 20).toLong() and SATURATED_32
                val uncompressedSize = central.getInt(at + 24).toLong() and SATURATED_32
                val nameLength = central.getShort(at + 28).toInt() and 0xFFFF
                val extraLength = central.getShort(at + 30).toInt() and 0xFFFF
                val commentLength = central.getShort(at + 32).toInt() and 0xFFFF
                var localOffset = central.getInt(at + 42).toLong() and SATURATED_32
                val next = at + CENTRAL_SIZE + nameLength + extraLength + commentLength
                if (next > central.capacity()) fail("a central directory record at $at runs past the central directory")
                if (flags and ENCRYPTED_FLAG != 0) fail("an entry is encrypted")
                if (method != ZipEntry.STORED && method != ZipEntry.DEFLATED) fail("an entry uses unsupported compression method $method")
                val nameBytes = ByteArray(nameLength).also { central.get(at + CENTRAL_SIZE, it) }
                if (compressedSize == SATURATED_32 || uncompressedSize == SATURATED_32 || localOffset == SATURATED_32) {
                    var extra = at + CENTRAL_SIZE + nameLength
                    val extraEnd = extra + extraLength
                    var found = false
                    while (extra + 4 <= extraEnd && !found) {
                        val id = central.getShort(extra).toInt() and 0xFFFF
                        val length = central.getShort(extra + 2).toInt() and 0xFFFF
                        if (id == ZIP64_EXTRA_ID) {
                            val needed =
                                listOf(uncompressedSize, compressedSize, localOffset).count { it == SATURATED_32 } * 8
                            if (length < needed || extra + 4 + length > extraEnd) fail("an entry's ZIP64 extra field is too short")
                            var field = extra + 4
                            if (uncompressedSize == SATURATED_32) field += 8
                            if (compressedSize == SATURATED_32) {
                                compressedSize = central.getLong(field)
                                field += 8
                            }
                            if (localOffset == SATURATED_32) localOffset = central.getLong(field)
                            found = true
                        }
                        extra += 4 + length
                    }
                    if (!found) fail("an entry's size is saturated but it has no ZIP64 extra field")
                }
                entries += CentralEntry(decode(nameBytes), method, compressedSize, localOffset)
                at = next
            }
            if (entries.size.toLong() != total) fail("the end record counts $total entries but the central directory holds ${entries.size}")
            return entries
        }

        private fun decode(bytes: ByteArray): String =
            try {
                Charsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            } catch (e: CharacterCodingException) {
                throw ZipException("$label: an entry name is not valid UTF-8").also { it.initCause(e) }
            }

        private fun assemble(
            entries: List<CentralEntry>,
            start: Long,
        ): JarContents {
            val classNames = linkedSetOf<String>()
            val versioned = linkedSetOf<String>()
            val poms = mutableListOf<Properties>()
            var manifestEntry: CentralEntry? = null
            for (e in entries) {
                if (e.name.endsWith("/")) continue
                val name = e.name
                when {
                    isClassEntry(name) -> classNames += JarContents.dottedName(name)
                    JarContents.POM_PROPERTIES.matches(name) -> poms += JarContents.loadProperties(data(e, start))
                    name.equals(JarContents.MANIFEST_ENTRY, ignoreCase = true) -> manifestEntry = e
                    else -> versionedClassPath(name)?.let { versioned += JarContents.dottedName(it) }
                }
            }
            val manifest = manifestEntry?.let { Manifest(ByteArrayInputStream(data(it, start))) }
            return JarContents(classNames, poms, manifest, versioned)
        }

        private fun data(
            e: CentralEntry,
            start: Long,
        ): ByteArray {
            val local = le(read(start + e.localOffset, LOCAL_SIZE))
            if (local.getInt(0) != LOCAL_SIGNATURE) fail("the local header of '${e.name}' has a bad signature")
            val nameLength = local.getShort(26).toInt() and 0xFFFF
            val extraLength = local.getShort(28).toInt() and 0xFFFF
            val dataAt = start + e.localOffset + LOCAL_SIZE + nameLength + extraLength
            if (e.compressedSize > Int.MAX_VALUE || dataAt + e.compressedSize > size) fail("the data of '${e.name}' runs past the entry")
            val raw = read(dataAt, e.compressedSize.toInt())
            return if (e.method == ZipEntry.STORED) raw else inflate(e, raw)
        }

        private fun inflate(
            e: CentralEntry,
            raw: ByteArray,
        ): ByteArray {
            val inflater = Inflater(true)
            try {
                inflater.setInput(raw)
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (!inflater.finished()) {
                    val n = inflater.inflate(buffer)
                    if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) fail("the data of '${e.name}' is truncated")
                    out.write(buffer, 0, n)
                }
                return out.toByteArray()
            } catch (ex: DataFormatException) {
                throw ZipException("$label: the data of '${e.name}' is not valid deflate data").also { it.initCause(ex) }
            } finally {
                inflater.end()
            }
        }
    }
}
