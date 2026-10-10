package com.example.target.inherit;

import java.lang.annotation.*;
import org.springframework.scheduling.annotation.Scheduled;

/** An adopter's annotation composed of a callback that never passes down. */
@Scheduled
@Retention(RetentionPolicy.RUNTIME)
public @interface ComposedScheduled {}
