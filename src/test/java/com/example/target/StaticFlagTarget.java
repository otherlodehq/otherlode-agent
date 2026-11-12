package com.example.target;

public class StaticFlagTarget {

    static final long STARTED = System.nanoTime();

    public StaticFlagTarget() {
    }

    public static int twice(int value) {
        return value > 0 ? value * 2 : 0;
    }

    public int plusOne(int value) {
        return value + 1;
    }
}
