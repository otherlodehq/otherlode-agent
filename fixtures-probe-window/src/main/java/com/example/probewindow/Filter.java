package com.example.probewindow;

// The default method makes initializing an implementation initialize this interface.
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
