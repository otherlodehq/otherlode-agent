package com.example.target.inherit;

import java.lang.annotation.*;
import org.axonframework.commandhandling.CommandHandler;

@CommandHandler
@Retention(RetentionPolicy.RUNTIME)
public @interface ComposedCommand {}
