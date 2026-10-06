package com.example.target.outsidecaller;

/** Overrides the three {@code Object} methods a collector sees most. */
public class ObjectOverrides {
    @Override
    public String toString() {
        return "x";
    }

    @Override
    public boolean equals(Object other) {
        return other == this;
    }

    @Override
    public int hashCode() {
        return 1;
    }

    public int unrelated() {
        return 2;
    }
}
