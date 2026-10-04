package com.example.probewindow;

/** Constructs the subclass before anything has initialized its superclass. */
public class SubclassConstantMain {
    public static void main(String[] args) {
        System.out.println("area " + new Circle().area());
    }
}
