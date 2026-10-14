package dev.otherlode.export

import java.lang.System.Logger.Level
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Holds the chunks of one once-per-process payload that the collector has not yet confirmed and
 * sends them in order. [StaticBaselineSender][dev.otherlode.instrumentation.staticscan.StaticBaselineSender]
 * and [ClassListingSender][dev.otherlode.classlisting.ClassListingSender] fix the payload.
 *
 * The payload is built once per process, so a chunk the collector never confirmed would otherwise
 * be lost for the life of the process. The builder offers the chunks and makes the first attempt
 * through [exporter]; [ExportScheduler] calls [retryPending] after a flush whose own sends the
 * collector confirmed, which is when a retry can succeed. A retry runs on the flush thread, so it
 * sends one chunk through [retryExporter], which should make one attempt: a flush then holds the
 * scheduler for one request timeout at most per sender, and the shutdown flush never retries.
 * A chunk that reached the collector with its answer lost is sent twice, which a collector reads
 * as one, since it keeps received chunks by index. A chunk the collector refuses for its content
 * (a 400 or 422) drops every pending chunk, since resending the same bytes would only be
 * refused again. Any other refusal keeps them, to be sent after a later flush the collector
 * confirms: a 401 while a token rotates, a 404 before a collector serves the route, a 413 since
 * a proxy's limit can be raised while the process runs.
 *
 * Only one thread sends at a time. A call that finds another sending returns at once rather than
 * wait, so the flush thread never blocks behind the builder thread's exporter backoff.
 *
 * [noun] names a chunk in the log and [whole] names what the chunks make up, as in "the static
 * baseline" and "the scan". [send] makes one export call.
 */
open class ChunkSender<T : Any>(
    private val exporter: Exporter,
    private val retryExporter: Exporter,
    private val noun: String,
    private val whole: String,
    private val send: (Exporter, T) -> Unit,
    private val chunkIndex: (T) -> Int,
    private val chunkCount: (T) -> Int,
) {
    private val log = System.getLogger(this::class.java.name)
    private val lock = ReentrantLock()

    /** Guarded by [lock]. */
    private val pending = ArrayDeque<T>()

    /** Guarded by [lock]. */
    private var failureLogged = false

    @Volatile
    private var hasPending = false

    /** Queues [chunks] behind anything still pending. */
    fun offer(chunks: List<T>) {
        lock.withLock {
            pending.addAll(chunks)
            hasPending = pending.isNotEmpty()
        }
    }

    /**
     * Sends up to [maxChunks] pending chunks in order, stopping at the first that fails. True when
     * nothing is left pending; false when a chunk failed, chunks are left over, or another thread
     * is sending.
     */
    fun sendPending(maxChunks: Int = Int.MAX_VALUE): Boolean = sendThrough(maxChunks, exporter)

    /** Sends the first pending chunk through [retryExporter], with the same answer as [sendPending]. */
    fun retryPending(): Boolean = sendThrough(1, retryExporter)

    private fun sendThrough(
        maxChunks: Int,
        exporter: Exporter,
    ): Boolean {
        if (!hasPending) return true
        if (!lock.tryLock()) return false
        try {
            var sent = 0
            while (pending.isNotEmpty()) {
                if (sent == maxChunks) return false
                val chunk = pending.first()
                try {
                    send(exporter, chunk)
                } catch (e: ExportFailedException) {
                    val status = e.statusCode
                    if (status == null || status !in REFUSED_CONTENT) return failed(chunk, e)
                    log.log(
                        Level.WARNING,
                        "otherlode: the collector refused $noun chunk ${chunkIndex(chunk) + 1} of ${chunkCount(chunk)} " +
                            "with status $status, which resending the same bytes cannot change; the $whole is not sent again",
                        e,
                    )
                    pending.clear()
                    hasPending = false
                    return true
                } catch (e: Exception) {
                    return failed(chunk, e)
                }
                pending.removeFirst()
                sent++
            }
            hasPending = false
            if (failureLogged) log.log(Level.INFO, "otherlode: the $noun reached the collector after a retry")
            return true
        } finally {
            lock.unlock()
        }
    }

    /** Logs the first failure that keeps the chunk; it stays pending. Called holding [lock]. */
    private fun failed(
        chunk: T,
        e: Exception,
    ): Boolean {
        if (!failureLogged) {
            failureLogged = true
            log.log(
                Level.WARNING,
                "otherlode: failed to send $noun chunk ${chunkIndex(chunk) + 1} of ${chunkCount(chunk)}; it and " +
                    "the chunks after it are sent again after a flush the collector confirms",
                e,
            )
        }
        return false
    }

    private companion object {
        /** Statuses that refuse a request's content, which the same bytes would meet again. */
        val REFUSED_CONTENT = setOf(400, 422)
    }
}
