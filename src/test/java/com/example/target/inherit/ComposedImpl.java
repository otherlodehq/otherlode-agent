package com.example.target.inherit;

/** Implements the composed-annotation interface without annotations. */
public class ComposedImpl implements ComposedApi {
    @Override
    public String get() {
        return "";
    }

    @Override
    public void tick() {}
}
