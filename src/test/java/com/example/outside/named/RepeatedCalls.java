package com.example.outside.named;

import java.lang.annotation.*;

/** The container javac writes when {@link RepeatedCall} is used twice. */
@Retention(RetentionPolicy.RUNTIME)
public @interface RepeatedCalls {
    RepeatedCall[] value();
}
