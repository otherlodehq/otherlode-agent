package com.example.target.inherit;

/** A workflow interface whose method carries a composed annotation. */
public interface WfComposedApi {
    @ComposedWorkflowMethod
    void run();
}
