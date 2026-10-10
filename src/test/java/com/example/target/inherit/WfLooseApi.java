package com.example.target.inherit;

import io.temporal.workflow.SignalMethod;

/** A signal on an interface that is not annotated as a workflow interface. */
public interface WfLooseApi {
    @SignalMethod
    void poke();
}
