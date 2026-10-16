package dev.otherlode.dependencies

import dev.otherlode.dependencies.TestJars.classEntry
import dev.otherlode.dependencies.TestJars.pom
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NestedJarReaderTest {
    @TempDir
    lateinit var dir: Path

    private val entryName = "BOOT-INF/lib/lib-1.0.jar"

    private fun read(
        nested: ByteArray,
        stored: Boolean = true,
    ): JarContents {
        val path = dir.resolve("outer-${System.nanoTime()}.jar")
        ZipOutputStream(Files.newOutputStream(path)).use { out ->
            val entry = ZipEntry(entryName)
            if (stored) {
                entry.method = ZipEntry.STORED
                entry.size = nested.size.toLong()
                entry.compressedSize = nested.size.toLong()
                entry.crc = CRC32().apply { update(nested) }.value
            }
            out.putNextEntry(entry)
            out.write(nested)
            out.closeEntry()
        }
        JarFile(path.toFile()).use { return NestedJarReader.read(it, it.getJarEntry(entryName)) }
    }

    private fun streamed(nested: ByteArray): JarContents = ZipInputStream(ByteArrayInputStream(nested)).use(JarContents::of)

    private fun assertSame(
        expected: JarContents,
        actual: JarContents,
    ) {
        assertEquals(expected.classNames, actual.classNames)
        assertEquals(expected.versionedClassNames, actual.versionedClassNames)
        assertEquals(expected.pomProperties, actual.pomProperties)
        assertEquals(expected.manifest?.mainAttributes, actual.manifest?.mainAttributes)
    }

    private fun entries(vararg names: String) = names.map { RawZip.stored(it, ByteArray(4)) }

    @Test
    fun `a ZIP64 nested jar is read from its ZIP64 end records`() {
        val nested = RawZip.build(entries("org/lib/A.class", "org/lib/B.class"), zip64 = true)

        assertEquals(setOf("org.lib.A", "org.lib.B"), read(nested).classNames)
    }

    @Test
    fun `ZIP64 end records are used beside an end record that is not saturated, as some writers leave it`() {
        val nested = RawZip.build(entries("org/lib/A.class", "org/lib/B.class"), zip64 = true, saturateEnd = false)

        assertEquals(setOf("org.lib.A", "org.lib.B"), read(nested).classNames)
        assertEquals(setOf("org.lib.A", "org.lib.B"), streamed(nested).classNames)
    }

    @Test
    fun `ZIP64 end records with a prefix are used beside an end record that is not saturated`() {
        val nested = RawZip.build(entries("org/lib/A.class"), prefix = ByteArray(9) { 2 }, zip64 = true, saturateEnd = false)

        assertEquals(setOf("org.lib.A"), read(nested).classNames)
    }

    @Test
    fun `a ZIP64 end record that disagrees with an unsaturated end record is ignored, as the JDK ignores it`() {
        val record =
            ByteBuffer
                .allocate(56)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0x06064b50)
                .putLong(44)
                .putShort(45)
                .putShort(45)
                .putInt(0)
                .putInt(0)
                .putLong(99)
                .putLong(99)
                .putLong(1234)
                .putLong(5678)
                .array()
        val locator =
            ByteBuffer
                .allocate(20)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0x07064b50)
                .putInt(0)
                .putLong(0)
                .putInt(1)
                .array()
        val nested =
            RawZip.build(
                listOf(
                    RawZip.stored("org/lib/A.class", ByteArray(4)),
                    RawZip.stored("org/lib/B.class", ByteArray(4), centralComment = record + locator),
                ),
            )

        assertEquals(setOf("org.lib.A", "org.lib.B"), read(nested).classNames)
    }

    @Test
    fun `a ZIP64 end record at the locator's offset is used whatever its own size field says, as the JDK and Spring Boot find it`() {
        val nested = RawZip.build(entries("org/lib/A.class", "org/lib/B.class"), zip64 = true, saturateEnd = false).copyOf()
        val buffer = ByteBuffer.wrap(nested).order(ByteOrder.LITTLE_ENDIAN)
        val recordAt = nested.size - 22 - 20 - 56
        assertEquals(0x06064b50, buffer.getInt(recordAt))
        buffer.putLong(recordAt + 4, 56)

        assertEquals(setOf("org.lib.A", "org.lib.B"), read(nested).classNames)
    }

    @Test
    fun `an entry's saturated sizes and offset are read from its ZIP64 extra field`() {
        val manifest = "Manifest-Version: 1.0\nImplementation-Title: lib\n\n".toByteArray()
        val nested =
            RawZip.build(
                listOf(
                    RawZip.stored("org/lib/A.class", ByteArray(4), zip64ExtraLength = 24),
                    RawZip.stored("META-INF/MANIFEST.MF", manifest, zip64ExtraLength = 24),
                ),
                zip64 = true,
            )

        val contents = read(nested)

        assertEquals(setOf("org.lib.A"), contents.classNames)
        assertEquals("lib", contents.manifest?.mainAttributes?.getValue("Implementation-Title"))
    }

    @Test
    fun `a ZIP64 extra field too short for the saturated fields is an unreadable jar`() {
        val nested = RawZip.build(listOf(RawZip.stored("org/lib/A.class", ByteArray(4), zip64ExtraLength = 16)), zip64 = true)

        assertFailsWith<ZipException> { read(nested) }
    }

    @Test
    fun `a nested jar with a comment, even one holding the end record's signature, is read`() {
        val comment = byteArrayOf(0x50, 0x4b, 0x05, 0x06) + "a comment long enough to be scanned".toByteArray()
        val nested = RawZip.build(entries("org/lib/A.class", "org/lib/B.class"), comment = comment)

        assertEquals(setOf("org.lib.A", "org.lib.B"), read(nested).classNames)
    }

    @Test
    fun `bytes after the last central record, fewer than a record, are ignored as Spring Boot reads only the counted records`() {
        val nested = RawZip.build(entries("org/lib/A.class"), centralTrailing = ByteArray(10))

        assertEquals(setOf("org.lib.A"), read(nested).classNames)
    }

    @Test
    fun `a ZIP64 nested jar with a prefix is read`() {
        val nested = RawZip.build(entries("org/lib/A.class"), prefix = ByteArray(11) { 1 }, zip64 = true)

        assertEquals(setOf("org.lib.A"), read(nested).classNames)
    }

    @Test
    fun `a nested jar with a prefix before its first entry is read, as a launch script prefixes a jar`() {
        val nested = RawZip.build(entries("org/lib/A.class", "org/lib/B.class"), prefix = "#!/bin/sh\nexit 0\n".toByteArray())

        val contents = read(nested)

        assertEquals(setOf("org.lib.A", "org.lib.B"), contents.classNames)
    }

    @Test
    fun `an entry name is decoded as UTF-8 whether or not the language encoding flag is set`() {
        val flagged = RawZip.stored("org/lib/Café.class", ByteArray(4), flags = 0x800)
        val unflagged = RawZip.stored("org/lib/Naïve.class", ByteArray(4))

        val contents = read(RawZip.build(listOf(flagged, unflagged)))

        assertEquals(setOf("org.lib.Café", "org.lib.Naïve"), contents.classNames)
        assertSame(streamed(RawZip.build(listOf(flagged, unflagged))), contents)
    }

    @Test
    fun `an entry name that is not valid UTF-8 is an unreadable jar`() {
        val bad = RawZip.stored("org/lib/X.class", ByteArray(4), nameBytes = byteArrayOf(0x6f, 0x72, 0x67, 0x2f, 0xC3.toByte(), 0x28))

        assertFailsWith<ZipException> { read(RawZip.build(listOf(bad))) }
    }

    @Test
    fun `a stored pom properties and manifest are read`() {
        val (pomName, pomBytes) = pom("org.lib", "lib", "1.0")
        val manifest = "Manifest-Version: 1.0\r\nAutomatic-Module-Name: org.lib\r\n\r\n".toByteArray()
        val nested =
            RawZip.build(
                listOf(
                    RawZip.stored("org/lib/A.class", ByteArray(4)),
                    RawZip.stored(pomName, pomBytes),
                    RawZip.stored("META-INF/MANIFEST.MF", manifest),
                ),
            )

        val contents = read(nested)

        assertEquals("1.0", contents.pomProperties.single().getProperty("version"))
        assertEquals("org.lib", contents.manifest?.mainAttributes?.getValue("Automatic-Module-Name"))
    }

    @Test
    fun `a deflated pom properties and manifest are read, the manifest name compared ignoring case`() {
        val (pomName, pomBytes) = pom("org.lib", "lib", "2.0")
        val manifest = "Manifest-Version: 1.0\r\nAutomatic-Module-Name: org.lib\r\n\r\n".toByteArray()
        val nested = RawZip.build(listOf(RawZip.deflated(pomName, pomBytes), RawZip.deflated("meta-inf/manifest.mf", manifest)))

        val contents = read(nested)

        assertEquals("2.0", contents.pomProperties.single().getProperty("version"))
        assertEquals("org.lib", contents.manifest?.mainAttributes?.getValue("Automatic-Module-Name"))
    }

    @Test
    fun `entries with a data descriptor are read by the central directory's sizes`() {
        val (pomName, pomBytes) = pom("org.lib", "lib", "3.0")
        val nested =
            RawZip.build(
                listOf(
                    RawZip.deflated(pomName, pomBytes, dataDescriptor = true),
                    RawZip.stored("org/lib/A.class", ByteArray(4), dataDescriptor = true),
                ),
            )

        val contents = read(nested)

        assertEquals("3.0", contents.pomProperties.single().getProperty("version"))
        assertEquals(setOf("org.lib.A"), contents.classNames)
    }

    @Test
    fun `a truncated central directory is an unreadable jar`() {
        val nested = RawZip.build(entries("org/lib/A.class", "org/lib/B.class"), centralBytesKept = 60)

        val error = assertFailsWith<ZipException> { read(nested) }

        assertNotNull(error.message)
    }

    @Test
    fun `an end record counting more entries than the central directory holds is an unreadable jar`() {
        val nested = RawZip.build(entries("org/lib/A.class", "org/lib/B.class")).also { it[it.size - 22 + 10] = 3 }

        assertFailsWith<ZipException> { read(nested) }
    }

    @Test
    fun `an encrypted entry is an unreadable jar`() {
        val nested = RawZip.build(listOf(RawZip.stored("org/lib/A.class", ByteArray(4), flags = 1)))

        assertFailsWith<ZipException> { read(nested) }
    }

    @Test
    fun `a nested jar with no end record or no entries is rejected`() {
        assertFailsWith<ZipException> { read(ByteArray(0)) }
        assertFailsWith<ZipException> { read("not a zip file, just some text that is long enough".toByteArray()) }
        assertFailsWith<ZipException> { read(RawZip.build(emptyList())) }
    }

    @Test
    fun `a nested jar the outer jar compresses is streamed and still listed`() {
        val nested = TestJars.bytes(listOf(pom("org.lib", "lib", "4.0"), classEntry("org.lib.A")))

        val contents = read(nested, stored = false)

        assertEquals(setOf("org.lib.A"), contents.classNames)
        assertEquals("4.0", contents.pomProperties.single().getProperty("version"))
    }

    @Test
    fun `an ordinary jar reads the same through the central directory as through a stream`() {
        val nested =
            TestJars.bytes(
                listOf(
                    classEntry("org.lib.A"),
                    classEntry("org.lib.inner.B"),
                    "META-INF/versions/21/org/lib/New.class" to ByteArray(4),
                    "org/lib/dir/" to ByteArray(0),
                    pom("org.lib", "lib", "5.0"),
                    pom("org.lib", "lib-two", "6.0"),
                    "META-INF/notes.txt" to ByteArray(200) { it.toByte() },
                ),
                manifest = mapOf("Automatic-Module-Name" to "org.lib"),
            )

        assertSame(streamed(nested), read(nested))
        assertTrue(read(nested).versionedClassNames.isNotEmpty())
    }
}
