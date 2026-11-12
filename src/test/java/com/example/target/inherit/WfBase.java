package com.example.target.inherit;

import io.temporal.workflow.SignalMethod;

public class WfBase {
    @SignalMethod
    public void signal() {}
}
