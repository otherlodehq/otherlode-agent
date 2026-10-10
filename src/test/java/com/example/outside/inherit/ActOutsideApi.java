package com.example.outside.inherit;

import io.temporal.activity.ActivityInterface;

/** An activity interface outside the instrumented packages. */
@ActivityInterface
public interface ActOutsideApi {
    String outside();
}
