package com.example.target.inherit;

import io.temporal.workflow.WorkflowMethod;
import java.lang.annotation.*;

/** An adopter's annotation composed of a workflow method, which Temporal does not see through. */
@WorkflowMethod
@Retention(RetentionPolicy.RUNTIME)
public @interface ComposedWorkflowMethod {}
