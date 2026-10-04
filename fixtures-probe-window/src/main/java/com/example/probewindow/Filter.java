package com.example.probewindow;

/** An interface with a default method, so initializing an implementation initializes it, and a constant holding an implementation. */
public interface Filter {
    Filter PLAIN = new Filter() {
        @Override
        public boolean matches(String s) {
            return s.startsWith("java.");
        }
    };

    boolean matches(String s);

    default boolean matchesAny(String... ss) {
        for (String s : ss) {
            if (matches(s)) {
                return true;
            }
        }
        return false;
    }
}
