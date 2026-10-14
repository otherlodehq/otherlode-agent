package dev.otherlode.classlisting

import dev.otherlode.export.IncludeRules
import dev.otherlode.export.ResourceAttributes
import java.lang.System.Logger.Level

/**
 * Runs one class listing and publishes it: chunks what [list] returns and sends the chunks in order
 * through [sender]. The first failure stops the send, and the chunks left are sent again after a
 * later flush the collector confirms.
 *
 * A null result from [list] means the classpath could not be read whole, and nothing is sent: the
 * server would read a partial listing as proof that the build lacks the classes it left out.
 */
class ClassListingPublisher(
    private val list: () -> List<String>?,
    private val includeRules: IncludeRules,
    private val sender: ClassListingSender,
    private val maxNamesPerChunk: Int = ClassListingChunker.DEFAULT_MAX_NAMES_PER_CHUNK,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val log = System.getLogger(ClassListingPublisher::class.java.name)

    /**
     * Lists, chunks and sends one listing stamped with [resource]. A failure is logged once and
     * never ends the thread with an uncaught throwable; chunks offered before it stay pending for
     * the scheduler to resend. A [VirtualMachineError] is rethrown after it is logged, since the
     * JVM itself is failing.
     */
    fun run(resource: ResourceAttributes) {
        try {
            val names = list() ?: return
            sender.offer(ClassListingChunker.chunk(names, resource, includeRules, clock(), maxNamesPerChunk))
            sender.sendPending()
        } catch (t: Throwable) {
            log.log(Level.WARNING, "otherlode: the class listing failed, so no class listing is sent", t)
            if (t is VirtualMachineError) throw t
        }
    }
}
