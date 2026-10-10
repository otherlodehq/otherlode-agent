package com.example.outside.named;

import java.lang.annotation.*;

/** Holds a nested annotation type, whose binary name has a dollar sign. */
public class Bus {
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Handler {}
}
