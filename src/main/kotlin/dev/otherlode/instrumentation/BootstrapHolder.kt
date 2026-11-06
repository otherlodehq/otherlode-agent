package dev.otherlode.instrumentation

import dev.otherlode.AgentLogger
import dev.otherlode.bootstrap.OtherlodeLoggers
import java.io.IOException
import java.lang.instrument.Instrumentation
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.time.Instant
import java.util.jar.JarFile

/** The agent cannot run without its bootstrap holder; see [BootstrapHolder.install]. */
class BootstrapInstallException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Puts the `:bootstrap` module's classes, the probe-array holder, the endpoint seam, the
 * verifier message ring and the shutdown hook table, onto the target JVM's bootstrap classloader.
 *
 * The holder jar ships embedded in the agent jar as a resource, under a name that does not end
 * in `.jar` because the shadow plugin would otherwise explode it into loose classes at build
 * time. `Instrumentation` only accepts a jar on disk, so it is copied to a temp file first, the
 * same approach OpenTelemetry's agent takes. A read-only filesystem or a full `java.io.tmpdir`
 * therefore makes the agent unusable, and [install] says so with a [BootstrapInstallException]
 * rather than leaving classes to fail in their own `<clinit>` later.
 */
object BootstrapHolder {
    const val DEFAULT_RESOURCE = "META-INF/otherlode/bootstrap-jar.bin"
    const val HOLDER_CLASS_NAME = "dev.otherlode.bootstrap.OtherlodeProbeArrays"
    const val ENDPOINTS_CLASS_NAME = "dev.otherlode.bootstrap.OtherlodeEndpoints"
    const val VERIFIER_MESSAGES_CLASS_NAME = "dev.otherlode.bootstrap.OtherlodeVerifierMessages"
    const val SHUTDOWN_HOOKS_CLASS_NAME = "dev.otherlode.bootstrap.OtherlodeShutdownHooks"
    const val LOGGERS_CLASS_NAME = "dev.otherlode.bootstrap.OtherlodeLoggers"

    /**
     * Idempotent: a JVM where the holder is already bootstrap-visible (a second install call, a
     * test suite installing repeatedly) is left alone.
     */
    @Synchronized
    fun install(
        instrumentation: Instrumentation,
        resourceName: String = DEFAULT_RESOURCE,
    ) {
        if (isInstalled()) {
            useStandardErrorFallback()
            return
        }
        val bytes = readEmbeddedJar(resourceName)
        val jar =
            try {
                val temp = Files.createTempFile("otherlode-bootstrap-", ".jar")
                ownJar = temp
                holdForLife(temp)
                temp.toFile().deleteOnExit()
                Files.write(temp, bytes)
                JarFile(temp.toFile())
            } catch (e: IOException) {
                throw BootstrapInstallException("otherlode: could not write the bootstrap holder jar to a temp file", e)
            }
        instrumentation.appendToBootstrapClassLoaderSearch(jar)
        if (!isInstalled()) {
            throw BootstrapInstallException("otherlode: appended the bootstrap holder jar but $HOLDER_CLASS_NAME is still not loadable")
        }
        useStandardErrorFallback()
    }

    private fun useStandardErrorFallback() {
        OtherlodeLoggers.setFallback { name -> AgentLogger(name, switches = false) }
    }

    /** The embedded holder jar's bytes, or a [BootstrapInstallException] naming the resource that was not there. */
    internal fun readEmbeddedJar(resourceName: String): ByteArray =
        BootstrapHolder::class.java.classLoader
            .getResourceAsStream(resourceName)
            ?.use { it.readBytes() }
            ?: throw BootstrapInstallException("otherlode: bootstrap holder jar not found on the agent classpath at $resourceName")

    /**
     * True only when every bootstrap-resident class is loadable from the bootstrap loader, not
     * just the first one. A stale embedded jar missing a class this agent added later would
     * otherwise pass this check and fail as a [NoClassDefFoundError] inside a framework class much
     * further downstream.
     */
    fun isInstalled(): Boolean =
        isLoadableFromBootstrap(HOLDER_CLASS_NAME) &&
            isLoadableFromBootstrap(ENDPOINTS_CLASS_NAME) &&
            isLoadableFromBootstrap(VERIFIER_MESSAGES_CLASS_NAME) &&
            isLoadableFromBootstrap(SHUTDOWN_HOOKS_CLASS_NAME) &&
            isLoadableFromBootstrap(LOGGERS_CLASS_NAME)

    private fun isLoadableFromBootstrap(className: String): Boolean =
        try {
            Class.forName(className, false, null)
            true
        } catch (_: ClassNotFoundException) {
            false
        }

    /**
     * Deletes holder jars that earlier processes left in [dir] when they were killed before
     * `deleteOnExit` ran. Each jar has a sibling `.lock` file that its JVM keeps locked while it
     * lives. A jar goes, with its lock file, only when its name is `otherlode-bootstrap-*.jar`, it
     * is not this JVM's own jar, it was last modified over [LEFTOVER_MIN_AGE] before [now], its lock
     * file exists, and no process holds that lock. A jar with no lock file stays, since nothing
     * proves it is abandoned. A stale unlocked lock file with no jar goes too. It reads nothing
     * else in [dir], skips a file it cannot open or delete, and never throws.
     */
    internal fun sweepLeftovers(
        dir: Path = Path.of(System.getProperty("java.io.tmpdir")),
        now: Instant = Instant.now(),
    ) {
        try {
            Files.newDirectoryStream(dir, "otherlode-bootstrap-*.jar").use { names ->
                for (file in names) sweepJar(file, now)
            }
            Files.newDirectoryStream(dir, "otherlode-bootstrap-*.jar.lock").use { names ->
                for (file in names) sweepOrphanLock(file, now)
            }
        } catch (_: IOException) {
        } catch (_: DirectoryIteratorException) {
        } catch (_: SecurityException) {
        } catch (_: UnsupportedOperationException) {
        }
    }

    private fun sweepJar(
        jar: Path,
        now: Instant,
    ) {
        try {
            if (isOwn(jar) || !isStale(jar, now)) return
            val lock = lockFileOf(jar)
            if (!Files.exists(lock)) return
            FileChannel.open(lock, StandardOpenOption.WRITE).use { channel ->
                val held = tryLockRegion(channel) ?: return
                try {
                    Files.deleteIfExists(jar)
                } finally {
                    held.release()
                }
            }
            Files.deleteIfExists(lock)
        } catch (_: IOException) {
        } catch (_: SecurityException) {
        } catch (_: UnsupportedOperationException) {
        }
    }

    private fun sweepOrphanLock(
        lock: Path,
        now: Instant,
    ) {
        try {
            val jar = jarOf(lock)
            if (Files.exists(jar) || isOwn(jar) || !isStale(lock, now)) return
            deleteIfUnlocked(lock)
        } catch (_: IOException) {
        } catch (_: SecurityException) {
        } catch (_: UnsupportedOperationException) {
        }
    }

    private fun isStale(
        file: Path,
        now: Instant,
    ): Boolean = !Files.getLastModifiedTime(file).toInstant().isAfter(now.minus(LEFTOVER_MIN_AGE))

    private fun isOwn(jar: Path): Boolean {
        val own = ownJar ?: return false
        return jar == own || (Files.exists(jar) && Files.isSameFile(jar, own))
    }

    private fun lockFileOf(jar: Path): Path = jar.resolveSibling(jar.fileName.toString() + LOCK_SUFFIX)

    private fun jarOf(lock: Path): Path = lock.resolveSibling(lock.fileName.toString().removeSuffix(LOCK_SUFFIX))

    private fun deleteIfUnlocked(lock: Path): Boolean {
        FileChannel.open(lock, StandardOpenOption.WRITE).use { channel ->
            val held = tryLockRegion(channel) ?: return false
            held.release()
        }
        Files.deleteIfExists(lock)
        return true
    }

    /**
     * Creates the lock file beside [jar], locks it, and keeps the channel in a static field so the
     * lock lasts until the process dies. The lock lives on a file nothing else in this process
     * opens, because closing any descriptor of a file drops the process's locks on it. Returns
     * null, and leaves no lock file, when the lock cannot be taken; the jar still works, but a
     * sweep keeps it.
     */
    internal fun holdForLife(jar: Path): FileLock? {
        val lockFile = lockFileOf(jar)
        var created = false
        return try {
            Files.createFile(lockFile)
            created = true
            if (!isWindows()) lockFile.toFile().deleteOnExit()
            val channel = FileChannel.open(lockFile, StandardOpenOption.WRITE)
            val lock = tryLockRegion(channel)
            if (lock == null) {
                channel.close()
                Files.deleteIfExists(lockFile)
            } else {
                heldChannel = channel
                heldLock = lock
            }
            lock
        } catch (_: IOException) {
            if (created) runCatching { Files.deleteIfExists(lockFile) }
            null
        } catch (_: UnsupportedOperationException) {
            if (created) runCatching { Files.deleteIfExists(lockFile) }
            null
        } catch (_: SecurityException) {
            if (created) runCatching { Files.deleteIfExists(lockFile) }
            null
        }
    }

    private fun isWindows(): Boolean = System.getProperty("os.name", "").startsWith("Windows")

    /**
     * Takes the exclusive lock on the byte at position 0, or returns null when another process or
     * this JVM holds it. Throws [IOException] or [UnsupportedOperationException] when the file
     * system cannot lock.
     */
    internal fun tryLockRegion(channel: FileChannel): FileLock? =
        try {
            channel.tryLock(0L, 1L, false)
        } catch (_: OverlappingFileLockException) {
            null
        }

    internal const val LOCK_SUFFIX = ".lock"
    internal val LEFTOVER_MIN_AGE: Duration = Duration.ofMinutes(10)

    @Volatile
    private var ownJar: Path? = null

    @Volatile
    private var heldChannel: FileChannel? = null

    @Volatile
    private var heldLock: FileLock? = null
}
