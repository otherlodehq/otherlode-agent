package dev.otherlode.bootstrap;

import java.util.ResourceBundle;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The only way the agent asks for a {@link System.Logger}.
 *
 * <p>The boot loader defines this class, so the JDK treats the caller as a system class and hands
 * out a surrogate logger. A caller from the application loader would make the JDK create the
 * {@code java.util.logging.LogManager} at once, before the application can choose its own with the
 * {@code java.util.logging.manager} property.
 *
 * <p>A {@code System.LoggerFinder} bridge, such as {@code log4j-jpl}, gets no surrogate: the first
 * logger requested for a system class starts the bridge's backend. While a bridge is present, a
 * logger from {@link #get} writes through the fallback set by {@link #setFallback} until the gate
 * opens, so an application can configure its logging in {@code main} first. The gate opens by
 * itself {@link #GATE_SECONDS} seconds after the first request. {@link #openGate} opens it at once;
 * the agent calls that only when the JVM is shutting down.
 */
public final class OtherlodeLoggers {
    /** How long the gate stays closed at most. */
    public static final long GATE_SECONDS = 10;

    private static final String SERVICE = "java.lang.System$LoggerFinder";
    private static final Object LOCK = new Object();
    private static volatile boolean gateOpen;
    private static volatile Function<String, System.Logger> fallback;

    private OtherlodeLoggers() {}

    private static final class Bridge {
        static final long STARTED = System.nanoTime();
        static final boolean PRESENT = detect();

        private static boolean detect() {
            try {
                if (ClassLoader.getSystemResources("META-INF/services/" + SERVICE).hasMoreElements()) {
                    return true;
                }
                for (Module module : ModuleLayer.boot().modules()) {
                    for (var provides : module.getDescriptor().provides()) {
                        if (provides.service().equals(SERVICE)) {
                            return true;
                        }
                    }
                }
            } catch (Exception | LinkageError e) {
                return false;
            }
            return false;
        }
    }

    /** Returns the logger named {@code name}, requested on behalf of a system class. */
    public static System.Logger get(String name) {
        return Bridge.PRESENT ? new GatedLogger(name) : System.getLogger(name);
    }

    /** True when a class-path jar or a boot-layer module provides {@code System.LoggerFinder}. Loads no class. */
    public static boolean bridgePresent() {
        return Bridge.PRESENT;
    }

    /** Sets how a gated logger is made while the gate is closed. Each logger it returns writes to standard error. */
    public static void setFallback(Function<String, System.Logger> factory) {
        fallback = factory;
    }

    /** True once the gate is open, by {@link #openGate} or by time. A volatile read once it is. */
    public static boolean isGateOpen() {
        if (gateOpen) {
            return true;
        }
        if (gateDelayMillis() <= 0) {
            gateOpen = true;
        }
        return gateOpen;
    }

    /** Opens the gate; loggers from {@link #get} then use the JDK's. */
    public static void openGate() {
        synchronized (LOCK) {
            gateOpen = true;
            LOCK.notifyAll();
        }
    }

    /** Milliseconds until the gate opens by itself; zero when there is no bridge or it is open. */
    public static long gateDelayMillis() {
        if (!Bridge.PRESENT || gateOpen) {
            return 0;
        }
        long left = TimeUnit.SECONDS.toNanos(GATE_SECONDS) - (System.nanoTime() - Bridge.STARTED);
        return Math.max(0, TimeUnit.NANOSECONDS.toMillis(left));
    }

    /** Returns once the gate is open. Opens it when {@link #GATE_SECONDS} have passed. Returns at once with no bridge. */
    public static void awaitGate() {
        if (!Bridge.PRESENT) {
            return;
        }
        synchronized (LOCK) {
            while (!isGateOpen()) {
                long left = gateDelayMillis();
                if (left <= 0) {
                    continue;
                }
                try {
                    LOCK.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private static final class GatedLogger implements System.Logger {
        private final String name;
        private volatile System.Logger real;
        private volatile System.Logger stand;

        GatedLogger(String name) {
            this.name = name;
        }

        private System.Logger current() {
            if (isGateOpen()) {
                return realLogger();
            }
            Function<String, System.Logger> factory = fallback;
            if (factory == null) {
                return realLogger();
            }
            System.Logger local = stand;
            if (local == null) {
                local = factory.apply(name);
                stand = local;
            }
            return local;
        }

        private System.Logger realLogger() {
            System.Logger local = real;
            if (local == null) {
                local = System.getLogger(name);
                real = local;
            }
            return local;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public boolean isLoggable(Level level) {
            return current().isLoggable(level);
        }

        @Override
        public void log(Level level, String msg) {
            current().log(level, msg);
        }

        @Override
        public void log(Level level, Supplier<String> msgSupplier) {
            current().log(level, msgSupplier);
        }

        @Override
        public void log(Level level, Object obj) {
            current().log(level, obj);
        }

        @Override
        public void log(Level level, String msg, Throwable thrown) {
            current().log(level, msg, thrown);
        }

        @Override
        public void log(Level level, Supplier<String> msgSupplier, Throwable thrown) {
            current().log(level, msgSupplier, thrown);
        }

        @Override
        public void log(Level level, String format, Object... params) {
            current().log(level, format, params);
        }

        @Override
        public void log(Level level, ResourceBundle bundle, String msg, Throwable thrown) {
            current().log(level, bundle, msg, thrown);
        }

        @Override
        public void log(Level level, ResourceBundle bundle, String format, Object... params) {
            current().log(level, bundle, format, params);
        }
    }
}
