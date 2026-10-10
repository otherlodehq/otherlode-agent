package com.example.target.named;

import java.lang.annotation.*;

/** An adopter's marker declared inside the instrumented package. */
@Retention(RetentionPolicy.RUNTIME)
public @interface InScopeCall {}
