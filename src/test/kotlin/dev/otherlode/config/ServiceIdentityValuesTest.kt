package dev.otherlode.config

import java.util.logging.Handler
import java.util.logging.LogRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.util.logging.Level as JulLevel
import java.util.logging.Logger as JulLogger

class ServiceIdentityValuesTest {
    private fun parse(
        args: String? = null,
        env: Map<String, String> = emptyMap(),
        properties: Map<String, String> = emptyMap(),
    ): AgentConfig = AgentConfig.parse(args, env = env::get, systemProperties = properties::get, detectServiceName = { null })

    @Test
    fun `a value of 1024 bytes is kept and one of 1025 is skipped with a warning naming the source and the limit`() {
        val kept = "a".repeat(1024)
        assertEquals(kept, ServiceIdentityValues.usable(kept, "the agent option serviceName"))

        val warnings = warningsFrom { assertNull(ServiceIdentityValues.usable("a".repeat(1025), "the agent option serviceName")) }

        assertEquals(1, warnings.size, "$warnings")
        assertTrue(warnings.single().contains("the agent option serviceName") && warnings.single().contains("1024"), "$warnings")
        assertTrue(!warnings.single().contains("aaaa"), "$warnings")
    }

    @Test
    fun `the limit counts UTF-8 bytes`() {
        val kept = "é".repeat(512)
        val skipped = "é".repeat(513)

        assertEquals(kept, ServiceIdentityValues.usable(kept, "source"))
        assertNull(ServiceIdentityValues.usable(skipped, "source"))
    }

    @Test
    fun `a too long service name from the first source falls through to the second`() {
        val config =
            parse(
                "serviceName=${"a".repeat(1025)}",
                properties = mapOf("otherlode.service.name" to "from-property"),
            )

        assertEquals("from-property", config.serviceName)
        assertEquals("a".repeat(1024), parse("serviceName=${"a".repeat(1024)}").serviceName)
    }

    @Test
    fun `a too long namespace falls through to the OpenTelemetry attribute`() {
        val config =
            parse(
                "serviceNamespace=${"n".repeat(1025)}",
                env = mapOf("OTEL_RESOURCE_ATTRIBUTES" to "service.namespace=shop"),
            )

        assertEquals("shop", config.serviceNamespace)
    }

    @Test
    fun `a too long service version falls through to the next source`() {
        assertEquals(
            "1.2",
            parse("serviceVersion=${"v".repeat(1025)}", properties = mapOf("otherlode.service.version" to "1.2")).serviceVersion,
        )
        assertNull(parse("serviceVersion=${"v".repeat(1025)}").serviceVersion)
    }

    @Test
    fun `a too long environment falls through to the next source, and 1024 bytes is kept`() {
        val fromProperty =
            parse(
                "environment=${"e".repeat(1025)}",
                properties = mapOf("otherlode.environment" to "staging"),
            )
        assertEquals("staging", fromProperty.environment)

        val fromOtel =
            parse(
                "environment=${"e".repeat(1025)}",
                env = mapOf("OTEL_RESOURCE_ATTRIBUTES" to "deployment.environment.name=prod"),
            )
        assertEquals("prod", fromOtel.environment)

        assertEquals("e".repeat(1024), parse("environment=${"e".repeat(1024)}").environment)
        assertNull(parse("environment=${"e".repeat(1025)}").environment)
    }

    @Test
    fun `a too long service instance id falls through, then to the generated id`() {
        val fromProperty =
            parse(
                "serviceInstanceId=${"i".repeat(1025)}",
                properties = mapOf("otherlode.service.instance.id" to "pod-7"),
            )
        assertEquals("pod-7", fromProperty.serviceInstanceId)

        val generated = parse("serviceInstanceId=${"i".repeat(1025)}").serviceInstanceId
        assertEquals(36, generated.length)
        assertEquals("i".repeat(1024), parse("serviceInstanceId=${"i".repeat(1024)}").serviceInstanceId)
    }

    @Test
    fun `a dot instance id is still kept, since only a service name or namespace is a URL segment`() {
        assertEquals(".", parse("serviceInstanceId=.").serviceInstanceId)
    }

    @Test
    fun `a value holding a NUL is skipped with a warning naming the source and not the value`() {
        val value = "pay\u0000ments"
        val warnings = warningsFrom { assertNull(ServiceIdentityValues.usable(value, "the agent option serviceName")) }

        assertEquals(1, warnings.size, "$warnings")
        assertTrue(warnings.single().contains("the agent option serviceName"), "$warnings")
        assertTrue(!warnings.single().contains("pay") && !warnings.single().contains("\u0000"), "$warnings")
        assertNull(ServiceIdentityValues.withinLimit(value, "source"))
    }

    @Test
    fun `a NUL in the first source of each identity value falls through to the next source`() {
        val nul = "a\u0000b"
        val config =
            parse(
                "serviceName=$nul,serviceNamespace=$nul,serviceVersion=$nul,serviceInstanceId=$nul,environment=$nul",
                properties =
                    mapOf(
                        "otherlode.service.name" to "name",
                        "otherlode.service.namespace" to "ns",
                        "otherlode.service.version" to "1.0",
                        "otherlode.service.instance.id" to "i-1",
                        "otherlode.environment" to "prod",
                    ),
            )

        assertEquals("name", config.serviceName)
        assertEquals("ns", config.serviceNamespace)
        assertEquals("1.0", config.serviceVersion)
        assertEquals("i-1", config.serviceInstanceId)
        assertEquals("prod", config.environment)
    }

    @Test
    fun `a NUL in an OpenTelemetry attribute is skipped`() {
        val config = parse(env = mapOf("OTEL_RESOURCE_ATTRIBUTES" to "service.name=a\u0000b,service.version=1\u00002"))

        assertEquals("unknown_service:java", config.serviceName)
        assertNull(config.serviceVersion)
    }

    private fun warningsFrom(block: () -> Unit): List<String> {
        val records = mutableListOf<LogRecord>()
        val handler =
            object : Handler() {
                override fun publish(record: LogRecord) {
                    records.add(record)
                }

                override fun flush() {}

                override fun close() {}
            }
        val julLogger = JulLogger.getLogger(ServiceIdentityValues::class.java.name)
        val originalLevel = julLogger.level
        julLogger.addHandler(handler)
        julLogger.level = JulLevel.ALL
        try {
            block()
        } finally {
            julLogger.removeHandler(handler)
            julLogger.level = originalLevel
        }
        return records.filter { it.level == JulLevel.WARNING }.mapNotNull { it.message }
    }
}
