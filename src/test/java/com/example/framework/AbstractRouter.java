package com.example.framework;

/** A framework class whose only hookable method is abstract, which Advice never weaves. */
public abstract class AbstractRouter {
    public abstract void route();
}
