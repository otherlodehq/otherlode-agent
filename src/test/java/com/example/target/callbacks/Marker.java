package com.example.target.callbacks;

import java.lang.annotation.*;

/** A runtime annotation that no framework calls methods by. */
@Retention(RetentionPolicy.RUNTIME)
public @interface Marker {}
