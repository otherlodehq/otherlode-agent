package com.example.probewindow;

public abstract class Shape {
    static final Shape UNIT = new Circle();

    abstract int area();
}
