package com.example.target.callbacks;

import java.lang.annotation.*;
import org.springframework.context.event.EventListener;

@Retention(RetentionPolicy.RUNTIME)
@CycleY
@EventListener
public @interface CycleX {}
