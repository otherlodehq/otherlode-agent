package com.example.outside.named;

import java.lang.annotation.*;

/** A repeatable marker with runtime retention. Used twice, javac writes only {@link RepeatedCalls}. */
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(RepeatedCalls.class)
public @interface RepeatedCall {}
