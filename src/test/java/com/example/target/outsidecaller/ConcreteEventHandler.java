package com.example.target.outsidecaller;

/** Implements the out-of-scope interface its in-scope superclass names. */
public class ConcreteEventHandler extends EventHandler {
    @Override
    public void onEvent(String name) {}

    @Override
    public void tick() {}
}
