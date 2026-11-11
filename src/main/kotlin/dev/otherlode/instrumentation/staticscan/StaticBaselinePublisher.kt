package dev.otherlode.instrumentation.staticscan

import dev.otherlode.agentLogger
import dev.otherlode.export.Exporter
import dev.otherlode.export.IncludeRules
import dev.otherlode.export.ResourceAttributes
import dev.otherlode.registry.ProbeRegistry
import java.lang.System.Logger.Level

/**
 * Runs one static baseline scan and publishes the result: primes the [StaticBaselineMismatchDetector],
 * checks the classes that registered dynamically before the scan finished, and sends the scan to the
 * receiver in chunks carrying [includeRules].
 *
 * References pass through [filterReferences] before chunking. Without one, or when it throws an [Exception],
 * every reference list is emptied. A scan holding a class too large for one chunk is not sent, and the agent
 * logs one WARNING that names the class and the bound.
 *
 * Chunks are sent in order through [sender], and the first failure stops the send. A refusal of the content
 * (400 or 422) drops every chunk not yet sent. After any other failure, the chunks left are sent again after
 * a later flush whose payloads the receiver stores.
 */
class StaticBaselinePublisher(
    private val scan: () -> StaticScanResult,
    exporter: Exporter,
    private val registry: ProbeRegistry,
    private val mismatchDetector: StaticBaselineMismatchDetector,
    private val maxEntriesPerChunk: Int = DEFAULT_MAX_ENTRIES_PER_CHUNK,
    private val filterReferences: (StaticScanResult) -> StaticScanResult = StaticScanResult::withoutReferences,
    private val sender: StaticBaselineSender = StaticBaselineSender(exporter),
    private val includeRules: IncludeRules? = null,
) {
    private val log by agentLogger(StaticBaselinePublisher::class.java.name)

    /**
     * Scans, checks the classes registered so far against the scan, and sends the baseline. A failure is
     * logged once and ends the run. Only a [VirtualMachineError] is rethrown, after it is logged.
     */
    fun run(resource: ResourceAttributes) {
        try {
            publish(resource)
        } catch (t: Throwable) {
            log.log(Level.WARNING, "otherlode: the static baseline failed, so no static baseline is sent", t)
            if (t is VirtualMachineError) throw t
        }
    }

    private fun publish(resource: ResourceAttributes) {
        val result = scan()
        mismatchDetector.knownClassNames = result.allClassNames()
        for (className in registry.registeredClassNames()) {
            if (mismatchDetector.shouldWarnAbout(className)) {
                log.log(
                    Level.WARNING,
                    "otherlode: $className registered dynamically before the static baseline scan finished and was not " +
                        "found by it; the static scan cannot see classes a server or plugin loader finds at run time, " +
                        "such as a deployed WAR, so this deployment may have such a blind spot",
                )
            }
        }

        val filtered =
            try {
                filterReferences(result)
            } catch (e: Exception) {
                log.log(Level.WARNING, "otherlode: could not filter the static baseline's references; it is sent without them", e)
                result.withoutReferences()
            }
        val chunks =
            try {
                StaticBaselineChunker.chunk(filtered, resource, System.currentTimeMillis(), maxEntriesPerChunk, includeRules)
            } catch (e: ClassTooLargeException) {
                log.log(
                    Level.WARNING,
                    "otherlode: the static baseline is not sent: ${e.className} alone exceeds ${e.bound.description} for one chunk, " +
                        "and a scan without it would read as complete",
                )
                return
            }
        sender.offer(chunks)
        sender.sendPending()
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES_PER_CHUNK: Int = 20_000
    }
}
