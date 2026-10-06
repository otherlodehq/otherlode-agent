package com.example.target.outsidecaller;

import com.example.target.outsidecaller.external.ExternalBase;

/** Overrides a protected method of an out-of-scope superclass. */
public class HookChild extends ExternalBase {
    @Override
    protected void hook() {}
}
