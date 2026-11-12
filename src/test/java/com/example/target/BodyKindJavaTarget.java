package com.example.target;

public class BodyKindJavaTarget {
    private final Runnable fromField = new Runnable() {
        public void run() {
        }
    };

    public Runnable localClass() {
        class Local implements Runnable {
            public void run() {
            }
        }
        return new Local();
    }

    public static class Nested {
    }
}
