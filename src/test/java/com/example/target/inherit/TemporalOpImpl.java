package com.example.target.inherit;

import io.temporal.nexus.TemporalOperation;

/** One method carries a Temporal operation itself, the other only through its interface. */
public class TemporalOpImpl implements TemporalOpApi {
    @Override
    public void op() {}

    @TemporalOperation
    public void own() {}

    @ComposedTemporalOperation
    public void composed() {}
}
