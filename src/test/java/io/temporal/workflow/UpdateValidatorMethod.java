package io.temporal.workflow;

import java.lang.annotation.*;

/** A test stand-in for the framework annotation of the same binary name. */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD})
public @interface UpdateValidatorMethod {}
