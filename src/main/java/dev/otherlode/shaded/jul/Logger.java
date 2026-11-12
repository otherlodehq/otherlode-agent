package dev.otherlode.shaded.jul;

import dev.otherlode.bootstrap.OtherlodeLoggers;
import java.util.logging.Level;

/**
 * Stands in for {@code java.util.logging.Logger} in the shaded protobuf classes. The shaded jar's
 * {@code relocate} rule points their calls here, and each call goes to {@link OtherlodeLoggers}.
 */
public final class Logger {
    private final System.Logger delegate;

    private Logger(System.Logger delegate) {
        this.delegate = delegate;
    }

    public static Logger getLogger(String name) {
        return new Logger(OtherlodeLoggers.get(name));
    }

    public void info(String message) {
        delegate.log(System.Logger.Level.INFO, message);
    }

    public void warning(String message) {
        delegate.log(System.Logger.Level.WARNING, message);
    }

    /** Logs {@code message} at the System.Logger level nearest to {@code level}. */
    public void log(Level level, String message) {
        delegate.log(systemLevel(level), message);
    }

    /** Logs {@code message} and {@code thrown} at the System.Logger level nearest to {@code level}. */
    public void log(Level level, String message, Throwable thrown) {
        delegate.log(systemLevel(level), message, thrown);
    }

    private static System.Logger.Level systemLevel(Level level) {
        int value = level.intValue();
        if (value >= Level.SEVERE.intValue()) return System.Logger.Level.ERROR;
        if (value >= Level.WARNING.intValue()) return System.Logger.Level.WARNING;
        if (value >= Level.INFO.intValue()) return System.Logger.Level.INFO;
        if (value >= Level.FINE.intValue()) return System.Logger.Level.DEBUG;
        return System.Logger.Level.TRACE;
    }
}
