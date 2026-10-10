package com.example.target.inherit;

/** Implements an unannotated sub-interface of an activity interface. */
public class ActMixedImpl implements ActMixedApi {
    @Override
    public String plain() {
        return "";
    }

    @Override
    public String extra() {
        return "";
    }
}
