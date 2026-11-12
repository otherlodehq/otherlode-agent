package com.example.target.inherit;

import io.temporal.nexus.TemporalOperation;
import java.lang.annotation.*;

@TemporalOperation
@Retention(RetentionPolicy.RUNTIME)
public @interface ComposedTemporalOperation {}
