package com.example.target.inherit;

import io.temporal.activity.ActivityInterface;

@ActivityInterface
public interface ActChildApi extends ActParentApi {
    String child();
}
