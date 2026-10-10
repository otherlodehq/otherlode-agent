package com.example.target.inherit;

import java.lang.annotation.*;
import org.springframework.jms.annotation.JmsListener;

/** A composed annotation carrying {@code @JmsListener}, which carries {@code @MessageMapping}. */
@Retention(RetentionPolicy.RUNTIME)
@JmsListener
public @interface ComposedJms {}
