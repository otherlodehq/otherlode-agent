package com.example.target.outsidecaller;

/** Overrides a method the out-of-scope class two levels up declares. */
public class LeafOfMiddle extends MiddleBase {
    @Override
    public void ping() {}

    @Override
    public void other() {}
}
