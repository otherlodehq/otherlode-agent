package com.example.target.inherit;

import java.lang.annotation.*;
import org.springframework.context.event.EventListener;

/** An adopter's annotation composed of a listener that passes down. */
@EventListener
@Retention(RetentionPolicy.RUNTIME)
public @interface ComposedListener {}
