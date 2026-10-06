package com.example.target.outsidecaller;

/** Makes an anonymous class that implements a JDK interface. */
public class Anonymous {
    public Runnable make() {
        return new Runnable() {
            @Override
            public void run() {}
        };
    }
}
