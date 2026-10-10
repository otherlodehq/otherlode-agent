package com.example.target.named;

import com.example.outside.named.ClassCall;
import java.lang.annotation.*;

/** Carries a named annotation that has class retention. */
@Retention(RetentionPolicy.RUNTIME)
@ClassCall
public @interface ComposedOfClass {}
