package com.example.target.callbacks;

import java.lang.annotation.*;
import org.springframework.context.event.EventListener;

/** An annotation whose class file the analyser test hides. */
@Retention(RetentionPolicy.RUNTIME)
@EventListener
public @interface Vanished {}
