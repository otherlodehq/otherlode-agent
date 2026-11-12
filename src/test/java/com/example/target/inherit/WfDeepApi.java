package com.example.target.inherit;

import io.temporal.workflow.QueryMethod;

public interface WfDeepApi extends WfLooseApi {
    @QueryMethod
    default String peek() {
        return "";
    }
}
