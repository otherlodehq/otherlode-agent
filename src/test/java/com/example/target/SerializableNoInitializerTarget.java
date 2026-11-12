package com.example.target;

import java.io.Serializable;

public class SerializableNoInitializerTarget implements Serializable {
    private int value;

    public int next() {
        return ++value;
    }
}
