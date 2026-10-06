package com.example.target.callbacks;

import java.lang.annotation.*;
import org.springframework.context.event.EventListener;

/** Carries CycleY, which carries this one back, and then a listed annotation. */
@Retention(RetentionPolicy.RUNTIME)
@CycleY
@EventListener
public @interface CycleX {}
