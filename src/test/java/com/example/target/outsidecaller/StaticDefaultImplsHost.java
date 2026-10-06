package com.example.target.outsidecaller;

/**
 * A class, not an interface, with a nested class named like kotlinc's default-body class and a
 * static method shaped like a default body.
 */
public class StaticDefaultImplsHost implements Runnable {
    @Override
    public void run() {}

    /** Not a Kotlin default-body class, since its outer type is a class. */
    public static class DefaultImpls {
        public static void run(StaticDefaultImplsHost host) {}
    }
}
