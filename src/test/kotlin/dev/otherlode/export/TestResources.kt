package dev.otherlode.export

import dev.otherlode.config.AgentConfig

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
            testRun = config.testRun,
        )
}
