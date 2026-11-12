package com.example.target.inherit;

public interface ComposedApi {
    @ComposedListener
    String get();

    @ComposedScheduled
    void tick();
}
