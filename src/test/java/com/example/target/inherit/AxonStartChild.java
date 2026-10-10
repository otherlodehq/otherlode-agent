package com.example.target.inherit;

/** Overrides a start handler with no annotation. */
public class AxonStartChild extends AxonStartBase {
    @Override
    public void start() {}
}
