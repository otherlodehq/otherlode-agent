package dev.otherlode.config

import dev.otherlode.agentLogger
import java.lang.System.Logger.Level
import java.net.URI
import java.time.Duration
import java.util.UUID

/**
 * Agent options, passed as comma-separated key=value pairs on the
 * `-javaagent:otherlode-agent.jar=key=value,key=value` command line.
 *
 * A value cannot contain a comma. Set such a value, and a secret such as a token, through the
 * matching system property or environment variable; see [parse].
 */
data class AgentConfig(
    val serviceName: String,
    /**
     * The group the service belongs to, as OpenTelemetry's `service.namespace`. It has no default, and
     * null is the unspecified namespace.
     */
    val serviceNamespace: String?,
    /** The service version. It has no default, and null is an unversioned service. */
    val serviceVersion: String?,
    val serviceInstanceId: String,
    val environment: String?,
    /**
     * Base URL of the receiver. The exporter appends `/v1/otherlode/{deltas,manifest,static-baseline,class-listing}`
     * to it, so [parse] refuses a URL with a query string or fragment.
     */
    val exportUrl: String,
    /** Sent to the receiver as `Authorization: Bearer <token>`. */
    val authToken: String?,
    /** How often the agent flushes. Set in whole seconds by `flushIntervalSeconds`. */
    val flushInterval: Duration,
    /**
     * How often the agent asks the JVM to return freed native memory to the operating system, on Linux.
     * Set in whole minutes by `trimNativeHeapIntervalMinutes`. [Duration.ZERO] turns the trim off.
     */
    val trimNativeHeapInterval: Duration,
    /**
     * The agent instruments only types under one of these prefixes. The option is required. When it
     * is empty, [dev.otherlode.Agent] refuses to start, logs one ERROR and instruments and exports
     * nothing.
     */
    val includePackages: List<String>,
    /**
     * The agent never instruments a type under one of these prefixes, even if [includePackages]
     * also matches it.
     */
    val excludePackages: List<String>,
    /**
     * Off by default. The static baseline reads every class under the include rules.
     */
    val staticBaselineEnabled: Boolean,
    /**
     * On by default. Once per process, a background thread lists the names of the classes the
     * classpath holds under the include rules and the agent sends them as a class listing.
     */
    val classListingEnabled: Boolean,
    /** On by default. When false, [dev.otherlode.Agent] logs one line and does nothing else. */
    val enabled: Boolean,
    /**
     * On by default. One switch for every endpoint module and the OpenTelemetry route bridge;
     * [disabledEndpointModules] keeps single modules off.
     * When false, the agent reports every module that would have installed in
     * `disabled_endpoint_modules` as kept off by configuration.
     */
    val endpointsEnabled: Boolean,
    /** Off by default, unlike [endpointsEnabled]. */
    val otelBridgeEnabled: Boolean,
    /**
     * Off by default. Marks every payload of this run as a test run.
     */
    val testRun: Boolean,
    /**
     * Annotation types the adopter names as callback annotations, in the order written. Empty unless
     * `callbackAnnotations` is set. [parse] drops an entry that is not a well-formed type name and
     * logs a WARNING.
     */
    val callbackAnnotations: List<CallbackAnnotationName>,
    /**
     * Endpoint module names the adopter keeps from installing, in the order written, trimmed and
     * without blanks. Empty unless `disabledEndpointModules` is set. [parse] does not check the names.
     */
    val disabledEndpointModules: List<String>,
    /**
     * Why the agent refuses this configuration, one message for each problem, or empty when it
     * accepts it. No message quotes the token, or any of the URL but an underscore host.
     */
    val refusals: List<String> = emptyList(),
) {
    /** Prints the service name, instance id and export URL, and masks [authToken]. */
    override fun toString(): String =
        "AgentConfig(serviceName=$serviceName, serviceInstanceId=$serviceInstanceId, exportUrl=$exportUrl, " +
            "authToken=${if (authToken == null) "null" else "****"})"

    companion object {
        /** OpenTelemetry's name for a Java service that names none. */
        const val DEFAULT_SERVICE_NAME = "unknown_service:java"

        /** The environment a test run reports to when no source names one. */
        const val TEST_RUN_ENVIRONMENT = "test"

        private const val DEFAULT_EXPORT_URL = "http://localhost:4319"
        private val DEFAULT_FLUSH_INTERVAL: Duration = Duration.ofSeconds(60)
        private val MAX_FLUSH_INTERVAL: Duration = Duration.ofDays(1)
        private val DEFAULT_TRIM_NATIVE_HEAP_INTERVAL: Duration = Duration.ofMinutes(60)
        private val MAX_TRIM_NATIVE_HEAP_INTERVAL: Duration = Duration.ofDays(1)
        private val log by agentLogger(AgentConfig::class.java.name)

        private val KNOWN_KEYS =
            setOf(
                "serviceName",
                "serviceNamespace",
                "serviceVersion",
                "serviceInstanceId",
                "environment",
                "exportUrl",
                "authToken",
                "flushIntervalSeconds",
                "trimNativeHeapIntervalMinutes",
                "includePackages",
                "excludePackages",
                "staticBaselineEnabled",
                "classListingEnabled",
                "enabled",
                "endpointsEnabled",
                "otelBridgeEnabled",
                "testRun",
                "callbackAnnotations",
                "disabledEndpointModules",
            )

        /**
         * Parses [agentArgs] into a config. Every option resolves the same way: the agent-args
         * string wins, then a JVM system property, then an environment variable, then the option's
         * default. [OptionNames] gives the property and variable names. A blank value at any source
         * counts as unset.
         *
         * The service name, namespace, version and environment go on past those three sources, and
         * the first value that is not blank and that the identity checks accept wins: OpenTelemetry's
         * own settings ([OtelResourceSettings]),
         * then for the name only [detectServiceName] and [DEFAULT_SERVICE_NAME], and for the
         * environment only [TEST_RUN_ENVIRONMENT] when [testRun] is on. [parse] never reads the instance id
         * from OpenTelemetry. The id is a random UUID per process unless an Otherlode source sets it.
         */
        fun parse(
            agentArgs: String?,
            env: (String) -> String? = System::getenv,
            systemProperties: (String) -> String? = System::getProperty,
            detectServiceName: () -> String? = { ServiceNameDetector.forThisProcess(env, systemProperties).detect() },
        ): AgentConfig {
            val (options, unknownKeys) = parseOptions(agentArgs)
            for (key in unknownKeys) {
                log.log(Level.WARNING, "otherlode: ignoring unknown agent option '$key' (known options: ${KNOWN_KEYS.sorted()})")
            }

            fun resolve(key: String): String? = resolveOption(key, options, systemProperties, env)

            val otel = OtelResourceSettings.resolve(systemProperties, env)

            val prefixes = parsePackagePrefixes(resolve("includePackages"))
            val excludedPrefixes = parsePackagePrefixes(resolve("excludePackages"))
            val sourcedExportUrl = resolveSourced("exportUrl", options, systemProperties, env)
            val (exportUrl, exportUrlRefusal) = parseExportUrl(sourcedExportUrl)
            val authToken = resolve("authToken")
            val tokenRefusal = authToken?.let { tokenRefusal("authToken", it, options, systemProperties, env) }
            val plainHttpRefusal =
                if (authToken != null && sourcedExportUrl != null && exportUrlRefusal == null) {
                    plainHttpOtherlodeRefusal(exportUrl, sourcedExportUrl.source)
                } else {
                    null
                }
            val refusals = listOfNotNull(exportUrlRefusal, tokenRefusal, plainHttpRefusal)
            if (refusals.isEmpty() && authToken != null) {
                if (sourcedExportUrl == null) {
                    log.log(
                        Level.WARNING,
                        "otherlode: authToken is set but exportUrl is not, so the token goes to the default $DEFAULT_EXPORT_URL",
                    )
                } else if (exportUrl.startsWith("http://") && !isLoopbackHost(URI(exportUrl).host)) {
                    log.log(Level.WARNING, "otherlode: exportUrl uses plain http, so the auth token is sent unencrypted")
                }
            }
            val testRun = parseBoolean("testRun", resolve("testRun"), default = false)
            return AgentConfig(
                serviceName =
                    resolveIdentity("serviceName", options, systemProperties, env)
                        ?: otel.serviceName
                        ?: ServiceIdentityValues.usable(detectedServiceName(detectServiceName), "service name detection")
                        ?: DEFAULT_SERVICE_NAME,
                serviceNamespace = resolveIdentity("serviceNamespace", options, systemProperties, env) ?: otel.serviceNamespace,
                serviceVersion =
                    resolveIdentity("serviceVersion", options, systemProperties, env, ServiceIdentityValues::withinLimit)
                        ?: otel.serviceVersion,
                serviceInstanceId =
                    resolveIdentity("serviceInstanceId", options, systemProperties, env, ServiceIdentityValues::withinLimit)
                        ?: UUID.randomUUID().toString(),
                environment =
                    resolveIdentity("environment", options, systemProperties, env, ServiceIdentityValues::withinLimit)
                        ?: otel.environment
                        ?: TEST_RUN_ENVIRONMENT.takeIf { testRun },
                exportUrl = exportUrl,
                authToken = authToken,
                flushInterval = parseFlushInterval(resolve("flushIntervalSeconds")),
                trimNativeHeapInterval = parseTrimNativeHeapInterval(resolve("trimNativeHeapIntervalMinutes")),
                includePackages = prefixes,
                excludePackages = excludedPrefixes,
                staticBaselineEnabled = parseBoolean("staticBaselineEnabled", resolve("staticBaselineEnabled"), default = false),
                classListingEnabled = parseBoolean("classListingEnabled", resolve("classListingEnabled"), default = true),
                enabled = parseBoolean("enabled", resolve("enabled"), default = true),
                endpointsEnabled = parseBoolean("endpointsEnabled", resolve("endpointsEnabled"), default = true),
                otelBridgeEnabled = parseBoolean("otelBridgeEnabled", resolve("otelBridgeEnabled"), default = false),
                testRun = testRun,
                callbackAnnotations = parseCallbackAnnotations(resolve("callbackAnnotations")),
                disabledEndpointModules = parseNameList(resolve("disabledEndpointModules")),
                refusals = refusals,
            )
        }

        private fun resolveOption(
            key: String,
            options: Map<String, String>,
            systemProperties: (String) -> String?,
            env: (String) -> String?,
        ): String? =
            valueOrNull(options[key])
                ?: valueOrNull(systemProperties(OptionNames.systemProperty(key)))
                ?: valueOrNull(env(OptionNames.environmentVariable(key)))

        private fun resolveIdentity(
            key: String,
            options: Map<String, String>,
            systemProperties: (String) -> String?,
            env: (String) -> String?,
            screen: (String?, String) -> String? = ServiceIdentityValues::usable,
        ): String? {
            val property = OptionNames.systemProperty(key)
            val variable = OptionNames.environmentVariable(key)
            return screen(valueOrNull(options[key]), "the agent option $key")
                ?: screen(valueOrNull(systemProperties(property)), "the system property $property")
                ?: screen(valueOrNull(env(variable)), "the environment variable $variable")
        }

        private class Sourced(
            val value: String,
            val source: String,
        )

        private fun resolveSourced(
            key: String,
            options: Map<String, String>,
            systemProperties: (String) -> String?,
            env: (String) -> String?,
        ): Sourced? {
            val property = OptionNames.systemProperty(key)
            val variable = OptionNames.environmentVariable(key)
            valueOrNull(options[key])?.let { return Sourced(it, "the agent option $key") }
            valueOrNull(systemProperties(property))?.let { return Sourced(it, "the system property $property") }
            return valueOrNull(env(variable))?.let { Sourced(it, "the environment variable $variable") }
        }

        private fun tokenRefusal(
            key: String,
            token: String,
            options: Map<String, String>,
            systemProperties: (String) -> String?,
            env: (String) -> String?,
        ): String? {
            if (token.all { it in '!'..'~' }) return null
            val source = resolveSourced(key, options, systemProperties, env)?.source
            return "otherlode: $key from $source holds a character outside visible ASCII (! to ~), so the agent is off for this JVM; " +
                "set a token of visible ASCII characters only"
        }

        private fun valueOrNull(raw: String?): String? = raw?.trim()?.ifBlank { null }

        private fun detectedServiceName(detect: () -> String?): String? =
            try {
                valueOrNull(detect())
            } catch (e: Exception) {
                null
            }

        private fun parseBoolean(
            key: String,
            raw: String?,
            default: Boolean,
        ): Boolean {
            if (raw == null) return default
            val parsed = raw.lowercase().toBooleanStrictOrNull()
            if (parsed == null) {
                log.log(Level.WARNING, "otherlode: $key must be 'true' or 'false', ignoring '$raw' and using the default of $default")
                return default
            }
            return parsed
        }

        private fun parsePackagePrefixes(raw: String?): List<String> {
            val prefixes =
                raw
                    ?.split(";")
                    ?.map { it.trim().trimEnd('.') }
                    ?.filter { it.isNotEmpty() }
                    ?: return emptyList()
            val (unusable, usable) = prefixes.partition { '*' in it || '/' in it }
            for (prefix in unusable) {
                val dotted = prefix.replace('/', '.').substringBefore('*').trimEnd('.')
                log.log(
                    Level.WARNING,
                    "otherlode: '$prefix' matches no class and is ignored: write a dotted package prefix with no " +
                        "wildcard" + if (dotted.isEmpty()) "" else ", such as '$dotted'",
                )
            }
            return usable
        }

        private fun parseNameList(raw: String?): List<String> =
            raw
                ?.split(";")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty()

        private fun parseCallbackAnnotations(raw: String?): List<CallbackAnnotationName> {
            val entries =
                raw
                    ?.split(";")
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?: return emptyList()
            val kept = mutableListOf<CallbackAnnotationName>()
            for (entry in entries) {
                if (isTypeName(entry)) {
                    kept += CallbackAnnotationName(entry, entry.replace('$', '.'))
                    continue
                }
                val corrected = entry.removePrefix("@").replace('/', '.').removeSuffix(".class")
                log.log(
                    Level.WARNING,
                    "otherlode: callbackAnnotations entry '$entry' is not a fully qualified annotation type name and is " +
                        "ignored: write the dotted " +
                        "name with its package and no wildcard" + if (isTypeName(corrected)) ", such as '$corrected'" else "",
                )
            }
            return kept
        }

        private fun isTypeName(name: String): Boolean {
            val parts = name.split('.')
            return parts.size >= 2 &&
                parts.last() != "class" &&
                parts.all { part ->
                    part.isNotEmpty() &&
                        Character.isJavaIdentifierStart(part[0]) &&
                        part.drop(1).all(Character::isJavaIdentifierPart)
                }
        }

        private fun parseExportUrl(sourced: Sourced?): Pair<String, String?> {
            if (sourced == null) return DEFAULT_EXPORT_URL to null
            val trimmed = sourced.value.trimEnd('/')
            val uri = runCatching { URI(trimmed) }.getOrNull()
            val hasUserInfo = uri?.rawUserInfo != null || '@' in trimmed.substringAfter("://", "")
            val underscoreHost =
                uri
                    ?.takeIf { it.host == null && !hasUserInfo }
                    ?.rawAuthority
                    ?.substringBefore(':')
                    ?.takeIf { '_' in it }
            val reason =
                when {
                    hasUserInfo -> {
                        "exportUrl must not hold an @ after the scheme, as a user name or password does, which the agent " +
                            "never sends; set the token with authToken, and write an @ in a path as %40"
                    }

                    underscoreHost != null -> {
                        "exportUrl's host '$underscoreHost' has an underscore, which a URL host name cannot " +
                            "contain; use a host name or alias without one, or an IP address"
                    }

                    uri == null || uri.scheme?.lowercase() !in setOf("http", "https") || uri.host == null -> {
                        "exportUrl must be an absolute http or https URL"
                    }

                    uri.rawQuery != null -> {
                        "exportUrl must not have a query string"
                    }

                    uri.rawFragment != null -> {
                        "exportUrl must not have a fragment"
                    }

                    else -> {
                        null
                    }
                }
            if (reason != null) {
                return DEFAULT_EXPORT_URL to
                    "otherlode: $reason; exportUrl from ${sourced.source} is refused, so the agent is off for this JVM"
            }
            val scheme = checkNotNull(uri).scheme
            return (scheme.lowercase() + trimmed.substring(scheme.length)) to null
        }

        private fun plainHttpOtherlodeRefusal(
            exportUrl: String,
            source: String,
        ): String? {
            if (!exportUrl.startsWith("http://")) return null
            val host = URI(exportUrl).host.lowercase().removeSuffix(".")
            if (host != "otherlode.dev" && !host.endsWith(".otherlode.dev")) return null
            return "otherlode: exportUrl must use https for an otherlode.dev host while authToken is set, " +
                "since the token is an ingest key; exportUrl from $source is refused, so the agent is off for this JVM"
        }

        private val IPV4_LOOPBACK = Regex("""127(\.\d{1,3}){3}""")

        private fun isLoopbackHost(host: String): Boolean {
            if (host.equals("localhost", ignoreCase = true) || host == "[::1]") return true
            if (!IPV4_LOOPBACK.matches(host)) return false
            return host.split('.').all { it.toInt() in 0..255 }
        }

        private fun parseFlushInterval(raw: String?): Duration {
            if (raw == null) return DEFAULT_FLUSH_INTERVAL
            val seconds = raw.toLongOrNull()?.takeIf { it in 1..MAX_FLUSH_INTERVAL.seconds }
            if (seconds == null) {
                log.log(
                    Level.WARNING,
                    "otherlode: flushIntervalSeconds must be a whole number from 1 to ${MAX_FLUSH_INTERVAL.seconds}, " +
                        "ignoring '$raw' and using the default of ${DEFAULT_FLUSH_INTERVAL.seconds}s",
                )
                return DEFAULT_FLUSH_INTERVAL
            }
            return Duration.ofSeconds(seconds)
        }

        private fun parseTrimNativeHeapInterval(raw: String?): Duration {
            if (raw == null) return DEFAULT_TRIM_NATIVE_HEAP_INTERVAL
            val minutes = raw.toLongOrNull()?.takeIf { it in 0..MAX_TRIM_NATIVE_HEAP_INTERVAL.toMinutes() }
            if (minutes == null) {
                log.log(
                    Level.WARNING,
                    "otherlode: trimNativeHeapIntervalMinutes must be a whole number from 0 to " +
                        "${MAX_TRIM_NATIVE_HEAP_INTERVAL.toMinutes()}, " +
                        "ignoring '$raw' and using the default of ${DEFAULT_TRIM_NATIVE_HEAP_INTERVAL.toMinutes()} minutes",
                )
                return DEFAULT_TRIM_NATIVE_HEAP_INTERVAL
            }
            return Duration.ofMinutes(minutes)
        }

        private fun parseOptions(agentArgs: String?): Pair<Map<String, String>, Set<String>> {
            if (agentArgs.isNullOrBlank()) return emptyMap<String, String>() to emptySet()
            val options = LinkedHashMap<String, String>()
            val unknownKeys = LinkedHashSet<String>()
            var afterToken = false
            var tailWarned = false
            agentArgs.split(",").forEachIndexed { index, pair ->
                val separator = pair.indexOf('=')
                val key = if (separator > 0) pair.take(separator).trim() else null
                if (key != null && key in KNOWN_KEYS) {
                    afterToken = key == "authToken"
                    tailWarned = false
                } else if (afterToken) {
                    if (!tailWarned) {
                        tailWarned = true
                        log.log(
                            Level.WARNING,
                            "otherlode: the authToken value in the agent arguments appears to contain a comma, which the " +
                                "argument string cannot carry, so the token may be cut short. Set the token through the system " +
                                "property otherlode.auth.token or the environment variable OTHERLODE_AUTH_TOKEN instead",
                        )
                    }
                } else if (key == null) {
                    log.log(
                        Level.WARNING,
                        "otherlode: ignoring malformed agent option ${index + 1} (no '=' after a key; expected key=value; a value " +
                            "cannot contain a comma, set such a value through a system property or environment variable instead)",
                    )
                } else {
                    unknownKeys.add(key)
                }
                if (key != null) options[key] = pair.substring(separator + 1).trim()
            }
            return options to unknownKeys
        }
    }
}
