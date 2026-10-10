package com.example.target.inherit;

import io.temporal.nexus.TemporalOperation;
import java.lang.annotation.*;

/** An adopter's annotation composed of a Temporal operation, which Temporal does not see through. */
@TemporalOperation
@Retention(RetentionPolicy.RUNTIME)
public @interface ComposedTemporalOperation {}
