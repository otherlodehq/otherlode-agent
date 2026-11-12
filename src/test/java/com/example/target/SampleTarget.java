package com.example.target;

// It has no runtime dependencies, because a test loads it through a bare class loader whose parent
// is the bootstrap loader.
public class SampleTarget {

    public String ping() {
        return "pong";
    }

    public String neverCalled() {
        return "dead code";
    }
}
