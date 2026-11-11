package dev.otherlode.instrumentation

import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.jar.JarFile
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BootstrapHolderSweepTest {
    @TempDir
    lateinit var dir: Path

    private val now = Instant.parse("2026-11-06T12:00:00Z")

    private fun file(
        name: String,
        age: Duration,
    ): Path {
        val file = dir.resolve(name)
        file.writeText("x")
        Files.setLastModifiedTime(file, FileTime.from(now.minus(age)))
        return file
    }

    private fun lockOf(jar: Path): Path = jar.resolveSibling(jar.fileName.toString() + ".lock")

    @Test
    fun `a stale jar whose lock file is unlocked is deleted with it`() {
        val jar = file("otherlode-bootstrap-1.jar", Duration.ofMinutes(11))
        val lock = file("otherlode-bootstrap-1.jar.lock", Duration.ofMinutes(11))
        BootstrapHolder.sweepLeftovers(dir, now)
        assertFalse(jar.exists())
        assertFalse(lock.exists())
    }

    @Test
    fun `a stale jar with no lock file is kept`() {
        val jar = file("otherlode-bootstrap-2.jar", Duration.ofDays(1))
        BootstrapHolder.sweepLeftovers(dir, now)
        assertTrue(jar.exists())
    }

    @Test
    fun `a fresh jar with an unlocked lock file is kept`() {
        val jar = file("otherlode-bootstrap-3.jar", Duration.ofMinutes(9))
        val lock = file("otherlode-bootstrap-3.jar.lock", Duration.ofMinutes(9))
        BootstrapHolder.sweepLeftovers(dir, now)
        assertTrue(jar.exists())
        assertTrue(lock.exists())
    }

    @Test
    fun `files with other names are untouched`() {
        val other = file("other.jar", Duration.ofDays(1))
        val text = file("otherlode-bootstrap-x.txt", Duration.ofDays(1))
        val otherLock = file("other.jar.lock", Duration.ofDays(1))
        BootstrapHolder.sweepLeftovers(dir, now)
        assertTrue(other.exists())
        assertTrue(text.exists())
        assertTrue(otherLock.exists())
    }

    @Test
    fun `an orphan lock file is deleted when stale and kept when fresh`() {
        val stale = file("otherlode-bootstrap-4.jar.lock", Duration.ofMinutes(11))
        val fresh = file("otherlode-bootstrap-5.jar.lock", Duration.ofMinutes(9))
        BootstrapHolder.sweepLeftovers(dir, now)
        assertFalse(stale.exists())
        assertTrue(fresh.exists())
    }

    @Test
    fun `a stale jar whose lock file a live process holds is kept until that process ends`() {
        val jar = file("otherlode-bootstrap-6.jar", Duration.ofHours(1))
        val lock = file("otherlode-bootstrap-6.jar.lock", Duration.ofHours(1))
        val child = startChild(LockOnlyInChildJvm::class.java.name, lock)
        try {
            BootstrapHolder.sweepLeftovers(dir, now)
            assertTrue(jar.exists())
            assertTrue(lock.exists())
        } finally {
            child.destroyForcibly()
            child.waitFor(30, TimeUnit.SECONDS)
        }
        BootstrapHolder.sweepLeftovers(dir, now)
        assertFalse(jar.exists())
        assertFalse(lock.exists())
    }

    @Test
    fun `an installed jar stays protected after its bytes are written and it is opened`() {
        val jar = dir.resolve("otherlode-bootstrap-7.jar")
        val child = startChild(InstallSequenceInChildJvm::class.java.name, jar)
        try {
            Files.setLastModifiedTime(jar, FileTime.from(now.minus(Duration.ofDays(1))))
            BootstrapHolder.sweepLeftovers(dir, now.plus(Duration.ofDays(30)))
            assertTrue(jar.exists(), "the jar a live process uses is kept")
            assertTrue(lockOf(jar).exists())
            FileChannel.open(lockOf(jar), StandardOpenOption.WRITE).use { channel ->
                assertEquals(null, BootstrapHolder.tryLockRegion(channel), "another process cannot take the lock")
            }
        } finally {
            child.destroyForcibly()
            child.waitFor(30, TimeUnit.SECONDS)
        }
        BootstrapHolder.sweepLeftovers(dir, now.plus(Duration.ofDays(30)))
        assertFalse(jar.exists())
    }

    private fun startChild(
        main: String,
        file: Path,
    ): Process {
        val java = File(System.getProperty("java.home"), "bin/java").absolutePath
        val process =
            ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), main, file.toString())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        try {
            val lines = process.inputStream.bufferedReader().lineSequence()
            assertEquals("locked", lines.firstOrNull { it == "locked" })
        } catch (e: Throwable) {
            process.destroyForcibly()
            throw e
        }
        return process
    }
}

object LockOnlyInChildJvm {
    @JvmStatic
    fun main(args: Array<String>) {
        val channel = FileChannel.open(Path.of(args[0]), StandardOpenOption.WRITE)
        if (BootstrapHolder.tryLockRegion(channel) == null) System.exit(2)
        println("locked")
        System.out.flush()
        Thread.sleep(120_000)
    }
}

object InstallSequenceInChildJvm {
    @JvmStatic
    fun main(args: Array<String>) {
        val jar = Path.of(args[0])
        Files.createFile(jar)
        BootstrapHolder.holdForLife(jar)
        Files.write(jar, minimalJar())
        JarFile(jar.toFile()).close()
        System.gc()
        println("locked")
        System.out.flush()
        Thread.sleep(120_000)
    }

    private fun minimalJar(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        java.util.jar.JarOutputStream(out).use { }
        return out.toByteArray()
    }
}
