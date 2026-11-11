package dev.otherlode.instrumentation.endpoints.otelbridge

import java.text.MessageFormat
import java.util.ResourceBundle

// Captures through System.Logger because something removes a java.util.logging handler after the agent starts.
class CapturingLoggerFinder : System.LoggerFinder() {
    override fun getLogger(
        name: String,
        module: Module,
    ): System.Logger = CapturingLogger(name)
}

private class CapturingLogger(
    private val loggerName: String,
) : System.Logger {
    override fun getName(): String = loggerName

    override fun isLoggable(level: System.Logger.Level): Boolean = level.severity >= System.Logger.Level.INFO.severity

    override fun log(
        level: System.Logger.Level,
        bundle: ResourceBundle?,
        msg: String?,
        thrown: Throwable?,
    ) {
        record(level, msg, thrown)
    }

    override fun log(
        level: System.Logger.Level,
        bundle: ResourceBundle?,
        format: String?,
        params: Array<out Any?>?,
    ) {
        record(level, if (format != null && !params.isNullOrEmpty()) MessageFormat.format(format, *params) else format, null)
    }

    private fun record(
        level: System.Logger.Level,
        message: String?,
        thrown: Throwable?,
    ) {
        if (!isLoggable(level)) return
        System.err.println("$level $loggerName: $message")
        thrown?.printStackTrace()
        if (level.severity >= System.Logger.Level.WARNING.severity && message != null && message.startsWith("otherlode:")) {
            PrefixTestAgent.warnings += message
        }
    }
}
