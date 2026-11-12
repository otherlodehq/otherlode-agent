package com.example.target.inherit;

import io.temporal.workflow.SignalMethod;

public interface WfLooseApi {
    @SignalMethod
    void poke();
}
