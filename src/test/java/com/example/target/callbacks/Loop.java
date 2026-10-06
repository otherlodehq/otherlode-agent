package com.example.target.callbacks;

import java.lang.annotation.*;

/** An annotation that carries itself, as a cycle. */
@Retention(RetentionPolicy.RUNTIME)
@Loop
@Marker
public @interface Loop {}
