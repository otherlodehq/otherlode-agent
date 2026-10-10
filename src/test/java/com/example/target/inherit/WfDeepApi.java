package com.example.target.inherit;

import io.temporal.workflow.QueryMethod;

/** Extends the loose interface and adds a default method that carries a query. */
public interface WfDeepApi extends WfLooseApi {
    @QueryMethod
    default String peek() {
        return "";
    }
}
