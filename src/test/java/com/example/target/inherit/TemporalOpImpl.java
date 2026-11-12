package com.example.target.inherit;

import io.temporal.nexus.TemporalOperation;

public class TemporalOpImpl implements TemporalOpApi {
    @Override
    public void op() {}

    @TemporalOperation
    public void own() {}

    @ComposedTemporalOperation
    public void composed() {}
}
