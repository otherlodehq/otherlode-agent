package com.example.target;

public class StaticInitTarget {

    public static final int TOUCHED = poke();

    static int poke() {
        return 7;
    }
}
