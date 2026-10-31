package com.example.target;

import java.io.Serializable;

/** Serializable, no serialVersionUID and no static initializer: the default UID depends on the class's members. */
public class SerializableNoInitializerTarget implements Serializable {
    private int value;

    public int next() {
        return ++value;
    }
}
