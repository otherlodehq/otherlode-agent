package com.example.probewindow;

/** A superclass whose static initializer calls a static method of its subclass. */
public class Base {
    static final int VALUE = Sub.pick(7);
}
