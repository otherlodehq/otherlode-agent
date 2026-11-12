package com.example.target;

import java.io.Serializable;

public class SerializableWithInitializerTarget implements Serializable {
    static final int STEP = Integer.parseInt("2");

    private int value;

    public int next() {
        value += STEP;
        return value;
    }
}
