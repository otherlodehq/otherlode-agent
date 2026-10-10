package com.example.outside.named;

import java.lang.annotation.*;

/** Carried by the {@code EventListener} stand-in, as Spring's carries {@code @Reflective}. */
@Retention(RetentionPolicy.RUNTIME)
public @interface FrameworkCarried {}
