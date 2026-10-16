package dev.otherlode.dependencies

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * One entry for [RawZip.build]: [payload] is the bytes as stored in the archive, so it may be corrupt.
 * A non-null [zip64ExtraLength] saturates the central record's sizes and offset and writes a ZIP64
 * extra field holding them, cut to that many bytes; 24 holds all three. [centralComment] is the
 * entry's comment in its central record.
 */
internal class RawEntry(
    val name: String,
    val payload: ByteArray,
    val method: Int = RawZip.STORED,
    val size: Int = payload.size,
    val crc: Long = 0,
    val flags: Int = 0,
    val dataDescriptor: Boolean = false,
    val junkBefore: ByteArray = ByteArray(0),
    val nameBytes: ByteArray = name.toByteArray(Charsets.UTF_8),
    val zip64ExtraLength: Int? = null,
    val centralComment: ByteArray = ByteArray(0),
)

/** Writes zip archives byte by byte, for shapes `java.util.zip` will not write. */
internal object RawZip {
    const val STORED = 0
    const val DEFLATED = 8

    /** A stored entry holding [content]. */
    fun stored(
        name: String,
        content: ByteArray,
        flags: Int = 0,
        dataDescriptor: Boolean = false,
        junkBefore: ByteArray = ByteArray(0),
        nameBytes: ByteArray = name.toByteArray(Charsets.UTF_8),
        zip64ExtraLength: Int? = null,
        centralComment: ByteArray = ByteArray(0),
    ) = RawEntry(
        name,
        content,
        STORED,
        content.size,
        crc(content),
        flags,
        dataDescriptor,
        junkBefore,
        nameBytes,
        zip64ExtraLength,
        centralComment,
    )

    /** A deflated entry whose content inflates to [content]. */
    fun deflated(
        name: String,
        content: ByteArray,
        dataDescriptor: Boolean = false,
    ) = RawEntry(name, deflate(content), DEFLATED, content.size, crc(content), 0, dataDescriptor)

    /** A deflated entry whose compressed bytes are not valid deflate data. */
    fun corruptDeflated(name: String) = RawEntry(name, ByteArray(16) { 0xFF.toByte() }, DEFLATED, 64, 0)

    private fun crc(content: ByteArray) = CRC32().apply { update(content) }.value

    private fun deflate(content: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        deflater.setInput(content)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()
        return out.toByteArray()
    }

    private fun le(capacity: Int) = ByteBuffer.allocate(capacity).order(ByteOrder.LITTLE_ENDIAN)

    /**
     * The archive of [entries]. [prefix] precedes the first entry without shifting any recorded offset, as
     * a launch script does. [zip64] writes ZIP64 end records and the saturated end record fields.
     * [centralBytesKept] cuts the central directory to that many bytes, leaving the end record claiming all.
     * [saturateEnd] false keeps the end record's own values beside the ZIP64 records, as some writers do.
     * [centralTrailing] follows the central directory's records and counts in its size. [comment] is the
     * archive comment.
     */
    fun build(
        entries: List<RawEntry>,
        prefix: ByteArray = ByteArray(0),
        zip64: Boolean = false,
        centralBytesKept: Int? = null,
        saturateEnd: Boolean = true,
        centralTrailing: ByteArray = ByteArray(0),
        comment: ByteArray = ByteArray(0),
    ): ByteArray {
        val body = ByteArrayOutputStream()
        val offsets = mutableListOf<Long>()
        for (e in entries) {
            body.write(e.junkBefore)
            offsets += body.size().toLong()
            val flags = e.flags or (if (e.dataDescriptor) 0x08 else 0)
            val local = le(30)
            local
                .putInt(0x04034b50)
                .putShort(20)
                .putShort(flags.toShort())
                .putShort(e.method.toShort())
                .putInt(0)
            if (e.dataDescriptor) {
                local.putInt(0).putInt(0).putInt(0)
            } else {
                local.putInt(e.crc.toInt()).putInt(e.payload.size).putInt(e.size)
            }
            local.putShort(e.nameBytes.size.toShort()).putShort(0)
            body.write(local.array())
            body.write(e.nameBytes)
            body.write(e.payload)
            if (e.dataDescriptor) {
                body.write(
                    le(16)
                        .putInt(0x08074b50)
                        .putInt(e.crc.toInt())
                        .putInt(e.payload.size)
                        .putInt(e.size)
                        .array(),
                )
            }
        }
        val central = ByteArrayOutputStream()
        entries.forEachIndexed { i, e ->
            val flags = e.flags or (if (e.dataDescriptor) 0x08 else 0)
            val record = le(46)
            record
                .putInt(0x02014b50)
                .putShort(20)
                .putShort(20)
                .putShort(flags.toShort())
                .putShort(e.method.toShort())
                .putInt(0)
            val extra =
                e.zip64ExtraLength?.let { length ->
                    val values =
                        le(24)
                            .putLong(e.size.toLong())
                            .putLong(e.payload.size.toLong())
                            .putLong(offsets[i])
                            .array()
                    le(4 + length)
                        .putShort(1)
                        .putShort(length.toShort())
                        .put(values, 0, length)
                        .array()
                } ?: ByteArray(0)
            if (e.zip64ExtraLength != null) {
                record.putInt(e.crc.toInt()).putInt(-1).putInt(-1)
            } else {
                record.putInt(e.crc.toInt()).putInt(e.payload.size).putInt(e.size)
            }
            record
                .putShort(e.nameBytes.size.toShort())
                .putShort(extra.size.toShort())
                .putShort(e.centralComment.size.toShort())
                .putShort(0)
                .putShort(0)
                .putInt(0)
            record.putInt(if (e.zip64ExtraLength != null) -1 else offsets[i].toInt())
            central.write(record.array())
            central.write(e.nameBytes)
            central.write(extra)
            central.write(e.centralComment)
        }
        central.write(centralTrailing)
        val centralBytes = central.toByteArray().let { if (centralBytesKept != null) it.copyOf(centralBytesKept) else it }
        val out = ByteArrayOutputStream()
        out.write(prefix)
        out.write(body.toByteArray())
        val centralOffset = body.size().toLong()
        out.write(centralBytes)
        val centralSize = central.size().toLong()
        if (zip64) {
            val recordStart = centralOffset + centralBytes.size
            val record = le(56)
            record
                .putInt(0x06064b50)
                .putLong(44)
                .putShort(45)
                .putShort(45)
                .putInt(0)
                .putInt(0)
            record
                .putLong(entries.size.toLong())
                .putLong(entries.size.toLong())
                .putLong(centralSize)
                .putLong(centralOffset)
            out.write(record.array())
            out.write(
                le(20)
                    .putInt(0x07064b50)
                    .putInt(0)
                    .putLong(recordStart)
                    .putInt(1)
                    .array(),
            )
            val count = if (saturateEnd) (-1).toShort() else entries.size.toShort()
            out.write(
                le(22)
                    .putInt(0x06054b50)
                    .putShort(0)
                    .putShort(0)
                    .putShort(count)
                    .putShort(count)
                    .putInt(if (saturateEnd) -1 else centralSize.toInt())
                    .putInt(if (saturateEnd) -1 else centralOffset.toInt())
                    .putShort(comment.size.toShort())
                    .array(),
            )
        } else {
            out.write(
                le(22)
                    .putInt(0x06054b50)
                    .putShort(0)
                    .putShort(0)
                    .putShort(entries.size.toShort())
                    .putShort(entries.size.toShort())
                    .putInt(centralSize.toInt())
                    .putInt(centralOffset.toInt())
                    .putShort(comment.size.toShort())
                    .array(),
            )
        }
        out.write(comment)
        return out.toByteArray()
    }
}
