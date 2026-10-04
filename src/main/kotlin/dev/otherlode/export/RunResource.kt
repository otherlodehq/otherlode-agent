package dev.otherlode.export

import dev.otherlode.Agent
import dev.otherlode.config.AgentConfig
import java.util.UUID

/** [config]'s identity with a new random run id. The agent calls this once per process; see ADR 0032. */
fun ResourceAttributes.Companion.forNewRun(config: AgentConfig): ResourceAttributes =
    ResourceAttributes(
        serviceName = config.serviceName,
        serviceVersion = config.serviceVersion,
        serviceInstanceId = config.serviceInstanceId,
        environment = config.environment,
        runId = UUID.randomUUID().toString(),
        serviceNamespace = config.serviceNamespace,
        testRun = config.testRun,
        agentVersion = implementationVersionOf(Agent::class.java),
    )

/**
 * The `Implementation-Version` of the jar [type] was loaded from, or an empty string when it has
 * none. The agent jar's manifest carries it, and a class loaded from a directory, as in a test
 * JVM, has no manifest at all.
 */
internal fun implementationVersionOf(type: Class<*>): String = type.`package`?.implementationVersion.orEmpty()
