package com.example.target.inherit;

/** An interface whose methods carry composed annotations. */
public interface ComposedApi {
    @ComposedListener
    String get();

    @ComposedScheduled
    void tick();
}
