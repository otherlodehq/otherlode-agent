package dev.otherlode.classlisting

import dev.otherlode.agentLogger
import dev.otherlode.export.IncludeRules
import dev.otherlode.export.ResourceAttributes
import java.lang.System.Logger.Level

/**
 * Runs one class listing and publishes it: chunks what [list] returns and sends the chunks in order
 * through [sender]. The first failure stops the send. A null result from [list] means the
 * classpath could not be read whole, and nothing is sent.
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
     * Lists, chunks and sends one listing stamped with [resource]. A failure is logged and not
     * thrown, except a [VirtualMachineError], which is rethrown after it is logged.
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
