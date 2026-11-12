package dev.otherlode.classlisting

import dev.otherlode.agentLogger
import dev.otherlode.export.IncludeRules
import dev.otherlode.export.ResourceAttributes
import java.lang.System.Logger.Level

/**
 * Runs one class listing and publishes it: chunks what [list] returns and sends the chunks in order
 * through [sender]. The first failure stops the send. A null result from [list] means [list] could
 * not read the whole classpath, and the publisher then sends nothing.
 */
class ClassListingPublisher(
    private val list: () -> List<String>?,
    private val includeRules: IncludeRules,
    private val sender: ClassListingSender,
    private val maxNamesPerChunk: Int = ClassListingChunker.DEFAULT_MAX_NAMES_PER_CHUNK,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val log by agentLogger(ClassListingPublisher::class.java.name)

    /**
     * Lists, chunks and sends one listing stamped with [resource]. It logs a failure and does not
     * throw it, except a [VirtualMachineError], which it logs and then rethrows.
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
