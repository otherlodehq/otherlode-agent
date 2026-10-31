package com.example.target;

import java.io.Serializable;

/** Serializable, no serialVersionUID and a static field initialiser, so the class has a static initializer. */
public class SerializableWithInitializerTarget implements Serializable {
    static final int STEP = Integer.parseInt("2");

    private int value;

    public int next() {
        value += STEP;
        return value;
    }
}
