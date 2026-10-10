package io.temporal.activity;

import java.lang.annotation.*;

/** A test stand-in for the framework annotation of the same binary name. */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE})
public @interface ActivityInterface {}
