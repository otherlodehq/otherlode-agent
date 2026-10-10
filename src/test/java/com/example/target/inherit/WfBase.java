package com.example.target.inherit;

import io.temporal.workflow.SignalMethod;

/** A superclass method with a signal annotation, which does not pass down. */
public class WfBase {
    @SignalMethod
    public void signal() {}
}
