package com.example.outside.named;

import java.lang.annotation.*;

/** An adopter's marker with runtime retention, declared outside the instrumented packages. */
@Retention(RetentionPolicy.RUNTIME)
public @interface RuntimeCall {}
