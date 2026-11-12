package com.example.target.outsidecaller;

public class Anonymous {
    public Runnable make() {
        return new Runnable() {
            @Override
            public void run() {}
        };
    }
}
