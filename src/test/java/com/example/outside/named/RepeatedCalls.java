package com.example.outside.named;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
public @interface RepeatedCalls {
    RepeatedCall[] value();
}
