package com.example.target.inherit;

import io.temporal.activity.ActivityInterface;

/** An activity interface that extends unannotated interfaces. */
@ActivityInterface
public interface ActChildApi extends ActParentApi {
    String child();
}
