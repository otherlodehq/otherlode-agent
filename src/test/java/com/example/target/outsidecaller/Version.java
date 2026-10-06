package com.example.target.outsidecaller;

/** A generic implementation, which javac gives a same-class bridge. */
public class Version implements Comparable<Version> {
    @Override
    public int compareTo(Version other) {
        return 0;
    }

    public int nothing() {
        return 0;
    }
}
