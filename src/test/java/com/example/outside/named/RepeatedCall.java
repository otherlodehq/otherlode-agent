package com.example.outside.named;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Repeatable(RepeatedCalls.class)
public @interface RepeatedCall {}
