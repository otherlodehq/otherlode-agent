package com.example.target.inherit;

/** Overrides interface methods that carry Axon 4 annotations. */
public class AxonApiImpl implements AxonApi {
    @Override
    public void event() {}

    @Override
    public void start() {}
}
