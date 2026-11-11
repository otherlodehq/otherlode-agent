package dev.otherlode.export

import dev.otherlode.agentLogger
import java.lang.System.Logger.Level
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Holds the chunks of one once-per-process payload that the receiver has not confirmed, and sends
 * them in order. [offer] queues chunks, [sendPending] sends them through [exporter], and
 * [retryPending] sends one through [retryExporter], which should make one attempt.
 *
 * A chunk the receiver refuses for its content (a 400 or 422) drops every pending chunk. Any other
 * failure keeps them for a later call. A chunk that reached the receiver with its answer lost is
 * sent twice. Only one thread sends at a time. A call that finds chunks pending and another thread
 * sending returns false at once.
 *
 * [noun] names a chunk in the log and [whole] names what the chunks make up. [send] makes one
 * export call.
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
    private val log by agentLogger(this::class.java.name)
    private val lock = ReentrantLock()

    // pending and failureLogged are guarded by lock.
    private val pending = ArrayDeque<T>()

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
     * Sends up to [maxChunks] pending chunks in order, stopping at the first that fails. Returns true
     * when nothing is left pending, and false when a chunk failed, chunks are left over, or another
     * thread is sending.
     */
    fun sendPending(maxChunks: Int = Int.MAX_VALUE): Boolean = sendThrough(maxChunks, exporter)

    /** Sends the first pending chunk through [retryExporter], with the same result as [sendPending]. */
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
                        "otherlode: $noun chunk ${chunkIndex(chunk) + 1} of ${chunkCount(chunk)} was refused " +
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
            if (failureLogged) log.log(Level.INFO, "otherlode: the $noun was stored after a retry")
            return true
        } finally {
            lock.unlock()
        }
    }

    private fun failed(
        chunk: T,
        e: Exception,
    ): Boolean {
        if (!failureLogged) {
            failureLogged = true
            log.log(
                Level.WARNING,
                "otherlode: failed to send $noun chunk ${chunkIndex(chunk) + 1} of ${chunkCount(chunk)}; it and " +
                    "the chunks after it are sent again after a flush whose payloads are stored",
                e,
            )
        }
        return false
    }

    private companion object {
        val REFUSED_CONTENT = setOf(400, 422)
    }
}
