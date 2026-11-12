package com.example.target.inherit;

import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;

@WorkflowInterface
public interface WfSelf {
    @SignalMethod
    default void signal() {}
}
