package com.example.testkittarget;

/**
 * Initialised and never instantiated, with a plain static method that never runs. It is the one
 * fixture whose static method is the author's own code and no proxy.
 */
public class IdleBoard {
    private static final String TITLE = String.valueOf(System.nanoTime());

    /** Never runs, since no instance exists. */
    public String render() {
        return TITLE;
    }

    /** A static method of a never-instantiated class. It can still run, so it is never folded. */
    public static String banner() {
        return "banner";
    }
}
