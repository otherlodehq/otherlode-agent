package com.example.target.inherit;

import java.lang.annotation.*;
import org.axonframework.commandhandling.CommandHandler;

/** An adopter's annotation composed of an Axon command handler. */
@CommandHandler
@Retention(RetentionPolicy.RUNTIME)
public @interface ComposedCommand {}
