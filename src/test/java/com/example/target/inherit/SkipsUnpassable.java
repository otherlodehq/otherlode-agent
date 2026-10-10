package com.example.target.inherit;

/** A matching superclass method that passes nothing down does not end the walk. */
public class SkipsUnpassable extends SkipBase implements NearA {
    @Override
    public void m() {}
}
