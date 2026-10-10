package org.springframework.jms.annotation;

import java.lang.annotation.*;
import org.springframework.messaging.handler.annotation.MessageMapping;

/**
 * A test stand-in for the framework annotation of the same binary name. Like Spring's, it is
 * meta-annotated {@code @MessageMapping}, whose rule passes down where its own does not.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE, ElementType.ANNOTATION_TYPE})
@MessageMapping
public @interface JmsListener {}
