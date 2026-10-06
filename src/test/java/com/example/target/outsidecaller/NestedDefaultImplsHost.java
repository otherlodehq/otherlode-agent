package com.example.target.outsidecaller;

/** Holds a nested class that happens to be named like kotlinc's default-body class. */
public class NestedDefaultImplsHost {
    /** Implements Runnable: its run() is an ordinary override, not a Kotlin default body. */
    public static class DefaultImpls implements Runnable {
        @Override
        public void run() {}
    }
}
