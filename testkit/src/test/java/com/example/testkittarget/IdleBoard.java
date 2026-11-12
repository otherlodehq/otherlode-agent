package com.example.testkittarget;

public class IdleBoard {
    private static final String TITLE = String.valueOf(System.nanoTime());

    public String render() {
        return TITLE;
    }

    public static String banner() {
        return "banner";
    }
}
