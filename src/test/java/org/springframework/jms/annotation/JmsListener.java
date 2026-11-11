package org.springframework.jms.annotation;

import java.lang.annotation.*;
import org.springframework.messaging.handler.annotation.MessageMapping;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE, ElementType.ANNOTATION_TYPE})
@MessageMapping
public @interface JmsListener {}
