package com.example.target;

// No runtime dependencies: a test loads it through a bare bootstrap-parented class loader.
public class SampleTarget {

    public String ping() {
        return "pong";
    }

    public String neverCalled() {
        return "dead code";
    }
}
