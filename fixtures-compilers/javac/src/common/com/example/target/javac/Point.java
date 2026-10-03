package com.example.target.javac;

/** A record with every member generated. */
public record Point(int i, String s, long l, double d, float f, boolean b, Object o) {
    public int extra() {
        return i;
    }
}
