package com.example.target.inherit;

import io.temporal.activity.ActivityInterface;

/** An activity interface whose methods carry no annotation. */
@ActivityInterface
public interface ActApi {
    String plain();

    default String withBody() {
        return "";
    }

    static String util() {
        return "";
    }
}
