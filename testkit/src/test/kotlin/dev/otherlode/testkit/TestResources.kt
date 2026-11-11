package dev.otherlode.testkit

import dev.otherlode.config.AgentConfig
import dev.otherlode.export.ResourceAttributes

object TestResources {
    const val RUN_ID = "run-1"

    fun forConfig(config: AgentConfig): ResourceAttributes =
        ResourceAttributes(
            serviceName = config.serviceName,
            serviceVersion = config.serviceVersion,
            serviceInstanceId = config.serviceInstanceId,
            environment = config.environment,
            runId = RUN_ID,
            serviceNamespace = config.serviceNamespace,
        )
}
