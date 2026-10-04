package com.example.probewindow;

/** A superclass whose static initializer constructs its own subclass. */
public abstract class Shape {
    static final Shape UNIT = new Circle();

    abstract int area();
}
