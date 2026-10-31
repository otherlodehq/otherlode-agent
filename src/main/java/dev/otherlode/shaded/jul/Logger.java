package dev.otherlode.shaded.jul;

import dev.otherlode.bootstrap.OtherlodeLoggers;
import java.util.logging.Level;

/**
 * Stands in for {@code java.util.logging.Logger} in the shaded protobuf classes, which ask for one
 * in a static initialiser. The real class would create the {@code java.util.logging.LogManager}
 * from a class the application loader defines, before the application can choose its own. The
 * shaded jar's {@code relocate} rule points those calls here, and each call goes to the boot
 * loader's {@link OtherlodeLoggers}.
 */
public final class Logger {
    private final System.Logger delegate;

    private Logger(System.Logger delegate) {
        this.delegate = delegate;
    }

    /** Returns the logger named {@code name}. */
    public static Logger getLogger(String name) {
        return new Logger(OtherlodeLoggers.get(name));
    }

    /** Logs {@code message} at INFO. */
    public void info(String message) {
        delegate.log(System.Logger.Level.INFO, message);
    }

    /** Logs {@code message} at WARNING. */
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
