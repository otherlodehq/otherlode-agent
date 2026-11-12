package com.example.target.inherit;

import io.temporal.workflow.WorkflowMethod;
import java.lang.annotation.*;

@WorkflowMethod
@Retention(RetentionPolicy.RUNTIME)
public @interface ComposedWorkflowMethod {}
