package com.example.target.inherit;

import io.temporal.nexus.TemporalOperation;

/** An interface method that carries a Temporal operation, which does not pass down. */
public interface TemporalOpApi {
    @TemporalOperation
    void op();
}
