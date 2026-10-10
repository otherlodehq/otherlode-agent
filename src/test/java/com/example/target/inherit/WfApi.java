package com.example.target.inherit;

import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.UpdateMethod;
import io.temporal.workflow.UpdateValidatorMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/** A Temporal workflow interface whose methods carry the interface-only annotations. */
@WorkflowInterface
public interface WfApi {
    @WorkflowMethod
    String run();

    @SignalMethod
    void signal();

    @QueryMethod
    String query();

    @UpdateMethod
    void update();

    @UpdateValidatorMethod
    void validateUpdate();
}
