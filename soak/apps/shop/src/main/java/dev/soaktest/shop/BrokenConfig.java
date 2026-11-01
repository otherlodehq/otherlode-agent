package dev.soaktest.shop;

import java.util.Objects;

public final class BrokenConfig {
    private static final String VALUE =
            Objects.requireNonNull(System.getProperty("soak.broken.config"), "soak.broken.config is not set");

    private BrokenConfig() {}

    public static String value() {
        return VALUE;
    }
}
