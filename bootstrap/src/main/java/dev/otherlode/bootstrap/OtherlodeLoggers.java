package dev.otherlode.bootstrap;

/**
 * The only way the agent asks for a {@link System.Logger}.
 *
 * <p>The boot loader defines this class, so the JDK treats the caller as a system class and hands
 * out a surrogate logger. A caller from the application loader would make the JDK create the
 * {@code java.util.logging.LogManager} at once, before the application can choose its own with the
 * {@code java.util.logging.manager} property.
 */
public final class OtherlodeLoggers {
    private OtherlodeLoggers() {}

    /** Returns the logger named {@code name}, requested on behalf of a system class. */
    public static System.Logger get(String name) {
        return System.getLogger(name);
    }
}
