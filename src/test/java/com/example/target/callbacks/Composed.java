package com.example.target.callbacks;

import java.lang.annotation.*;
import org.springframework.context.event.EventListener;

/** An adopter's annotation that carries a listed one. */
@Retention(RetentionPolicy.RUNTIME)
@EventListener
public @interface Composed {}
