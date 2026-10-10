package com.example.target.named;

import com.example.outside.named.RuntimeCall;
import java.lang.annotation.*;

/** Carries a named annotation that has runtime retention. */
@Retention(RetentionPolicy.RUNTIME)
@RuntimeCall
public @interface ComposedOfRuntime {}
