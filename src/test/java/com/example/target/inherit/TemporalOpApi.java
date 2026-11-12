package com.example.target.inherit;

import io.temporal.nexus.TemporalOperation;

public interface TemporalOpApi {
    @TemporalOperation
    void op();
}
