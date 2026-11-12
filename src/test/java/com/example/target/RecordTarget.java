package com.example.target;

public record RecordTarget(int x, String y) {
    public int extra() {
        return x;
    }
}

enum JavaColour {
    RED,
    GREEN,
}
