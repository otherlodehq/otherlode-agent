package com.example.target.inherit;

import java.lang.annotation.*;
import org.springframework.context.event.EventListener;

@EventListener
@Retention(RetentionPolicy.RUNTIME)
public @interface ComposedListener {}
