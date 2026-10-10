package com.example.target.inherit;

import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;

/** A workflow interface whose signal method has a default body, which runs when nothing overrides it. */
@WorkflowInterface
public interface WfSelf {
    @SignalMethod
    default void signal() {}
}
