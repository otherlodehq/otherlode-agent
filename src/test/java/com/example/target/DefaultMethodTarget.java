package com.example.target;

public interface DefaultMethodTarget {

    default String defaultThing() {
        return "default";
    }

    static String staticThing() {
        return "static";
    }

    String abstractThing();
}
