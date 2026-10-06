package com.example.target.outsidecaller;

import com.example.target.outsidecaller.external.ExternalBase;

/** An in-scope abstract class between an out-of-scope superclass and its subclass. */
public abstract class MiddleBase extends ExternalBase {
    public abstract void other();
}
