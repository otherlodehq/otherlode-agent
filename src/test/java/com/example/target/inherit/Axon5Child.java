package com.example.target.inherit;

/** Overrides Axon 5 methods with no annotation. */
public class Axon5Child extends Axon5Base {
    @Override
    public void command() {}

    @Override
    public void creator() {}
}
