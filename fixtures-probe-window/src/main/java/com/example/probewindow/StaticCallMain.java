package com.example.probewindow;

/** Calls the subclass's static method before anything has initialized its superclass. */
public class StaticCallMain {
    public static void main(String[] args) {
        System.out.println("pick " + Sub.pick(1));
    }
}
