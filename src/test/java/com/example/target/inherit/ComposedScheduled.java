package com.example.target.inherit;

import java.lang.annotation.*;
import org.springframework.scheduling.annotation.Scheduled;

@Scheduled
@Retention(RetentionPolicy.RUNTIME)
public @interface ComposedScheduled {}
