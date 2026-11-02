package dev.otherlode

import dev.otherlode.bootstrap.OtherlodeLoggers
import dev.otherlode.instrumentation.BootstrapHolder
import java.io.PrintWriter
import java.io.StringWriter
import java.text.MessageFormat
import java.time.ZonedDateTime
import java.util.ResourceBundle
import java.util.function.Supplier
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

/**
 * A logger for the agent's classes. Once the agent has put the bootstrap jar on the boot class
 * path, it logs through the boot loader's [OtherlodeLoggers], so the JDK does not create the
 * application's `java.util.logging.LogManager` on the agent's behalf. Before that, and in a JVM
 * where the jar is never appended, it writes to standard error in the format of the JDK's default
 * console logger. It never asks the JDK for a logger itself. A logger created after the install is
 * the boot loader's own, with no wrapper unless a `System.LoggerFinder` bridge is present; then
 * [OtherlodeLoggers] writes through [AgentLogger] until its gate opens.
 */
internal fun bootLogger(name: String): System.Logger =
    if (BootstrapHolder.isInstalled()) OtherlodeLoggers.get(name) else AgentLogger(name, switches = true)

/**
 * Whether the agent writes its log lines to standard error whatever the application's logging
 * holds. It starts false. The shutdown hook sets it when it starts, since the JVM runs
 * `java.util.logging`'s hook at the same time, that hook resets every handler, and a line through
 * it is then lost.
 */
internal object StandardErrorLogging {
    @Volatile var on: Boolean = false
}

/**
 * The logger of a class, as a property delegate: [bootLogger] until [StandardErrorLogging] is on,
 * then a writer to standard error in the console format of ADR 0081. The logger is made at its
 * first use, as a `by lazy` field is, and a call goes straight to the logger, so a record names
 * the class that logged.
 */
internal fun agentLogger(name: String): ReadOnlyProperty<Any?, System.Logger> = SwitchedLogger(name)

private class SwitchedLogger(
    private val name: String,
) : ReadOnlyProperty<Any?, System.Logger> {
    private val normal = lazy { bootLogger(name) }
    private val standardError = lazy { AgentLogger(name, switches = false) }

    override fun getValue(
        thisRef: Any?,
        property: KProperty<*>,
    ): System.Logger = if (StandardErrorLogging.on) standardError.value else normal.value
}

/**
 * Every overload the agent calls is overridden, because the JDK's default methods pass a null
 * parameter array to the abstract vararg overload, which Kotlin's null check rejects.
 */
internal class AgentLogger(
    private val loggerName: String,
    private val switches: Boolean,
) : System.Logger {
    @Volatile private var boot: System.Logger? = null

    private fun delegate(): System.Logger? {
        boot?.let { return it }
        if (!switches || !BootstrapHolder.isInstalled()) return null
        return OtherlodeLoggers.get(loggerName).also { boot = it }
    }

    override fun getName(): String = loggerName

    override fun isLoggable(level: System.Logger.Level): Boolean =
        delegate()?.isLoggable(level) ?: (level != System.Logger.Level.OFF && level.severity >= System.Logger.Level.INFO.severity)

    override fun log(
        level: System.Logger.Level,
        msg: String?,
    ) = log(level, null as ResourceBundle?, msg, null as Throwable?)

    override fun log(
        level: System.Logger.Level,
        msg: String?,
        thrown: Throwable?,
    ) = log(level, null as ResourceBundle?, msg, thrown)

    override fun log(
        level: System.Logger.Level,
        msgSupplier: Supplier<String>,
    ) {
        if (isLoggable(level)) log(level, null as ResourceBundle?, msgSupplier.get(), null as Throwable?)
    }

    override fun log(
        level: System.Logger.Level,
        obj: Any,
    ) {
        if (isLoggable(level)) log(level, null as ResourceBundle?, obj.toString(), null as Throwable?)
    }

    override fun log(
        level: System.Logger.Level,
        msgSupplier: Supplier<String>,
        thrown: Throwable,
    ) {
        if (isLoggable(level)) log(level, null as ResourceBundle?, msgSupplier.get(), thrown)
    }

    override fun log(
        level: System.Logger.Level,
        bundle: ResourceBundle?,
        msg: String?,
        thrown: Throwable?,
    ) {
        val boot = delegate()
        if (boot != null) {
            boot.log(level, bundle, msg, thrown)
        } else if (isLoggable(level)) {
            writeToStderr(level, localized(bundle, msg), thrown)
        }
    }

    override fun log(
        level: System.Logger.Level,
        bundle: ResourceBundle?,
        format: String?,
        vararg params: Any?,
    ) {
        val boot = delegate()
        if (boot != null) {
            boot.log(level, bundle, format, *params)
        } else if (isLoggable(level)) {
            val text = localized(bundle, format)
            writeToStderr(level, if (params.isEmpty() || text == null) text else MessageFormat.format(text, *params), null)
        }
    }

    private fun localized(
        bundle: ResourceBundle?,
        key: String?,
    ): String? = if (bundle != null && key != null && bundle.containsKey(key)) bundle.getString(key) else key

    private fun writeToStderr(
        level: System.Logger.Level,
        message: String?,
        thrown: Throwable?,
    ) {
        val self = AgentLogger::class.java.name
        val gate = BootstrapHolder.LOGGERS_CLASS_NAME
        val stub = "dev.otherlode.shaded.jul.Logger"
        val source =
            StackWalker.getInstance().walk { frames ->
                frames
                    .filter { it.className != self && !it.className.startsWith(gate) && it.className != stub }
                    .findFirst()
                    .map { "${it.className} ${it.methodName}" }
                    .orElse(loggerName)
            }
        val trace =
            thrown?.let {
                val out = StringWriter()
                PrintWriter(out).use { writer ->
                    writer.println()
                    it.printStackTrace(writer)
                }
                out.toString()
            } ?: ""
        val levelName = if (level == System.Logger.Level.ERROR) "SEVERE" else level.name
        val now = ZonedDateTime.now()
        val line =
            try {
                String.format(
                    System.getProperty("java.util.logging.SimpleFormatter.format", DEFAULT_FORMAT),
                    now,
                    source,
                    loggerName,
                    levelName,
                    message,
                    trace,
                )
            } catch (_: RuntimeException) {
                String.format(DEFAULT_FORMAT, now, source, loggerName, levelName, message, trace)
            }
        System.err.print(line)
        System.err.flush()
    }

    private companion object {
        const val DEFAULT_FORMAT = "%1\$tb %1\$td, %1\$tY %1\$tl:%1\$tM:%1\$tS %1\$Tp %2\$s%n%4\$s: %5\$s%6\$s%n"
    }
}
