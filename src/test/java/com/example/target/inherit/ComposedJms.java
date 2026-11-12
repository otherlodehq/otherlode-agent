package com.example.target.inherit;

import java.lang.annotation.*;
import org.springframework.jms.annotation.JmsListener;

@Retention(RetentionPolicy.RUNTIME)
@JmsListener
public @interface ComposedJms {}
