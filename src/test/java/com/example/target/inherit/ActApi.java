package com.example.target.inherit;

import io.temporal.activity.ActivityInterface;

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
