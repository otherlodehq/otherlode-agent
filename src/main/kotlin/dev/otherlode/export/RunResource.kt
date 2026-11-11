package dev.otherlode.export

import dev.otherlode.Agent
import dev.otherlode.config.AgentConfig
import java.util.UUID

/** [config]'s identity with a new random run id. */
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
        flushIntervalSeconds = config.flushInterval.seconds.toInt(),
    )

internal fun implementationVersionOf(type: Class<*>): String = type.`package`?.implementationVersion.orEmpty()
