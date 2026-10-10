package com.example.target.inherit;

import io.temporal.workflow.WorkflowMethod;

/** Carries a workflow annotation on the implementation method, which Temporal never reads. */
public class WfOwn {
    @WorkflowMethod
    public void run() {}
}
