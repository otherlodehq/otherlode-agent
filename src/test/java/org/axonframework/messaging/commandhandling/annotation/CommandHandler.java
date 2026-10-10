package org.axonframework.messaging.commandhandling.annotation;

import java.lang.annotation.*;
import org.axonframework.messaging.core.annotation.MessageHandler;

/** A test stand-in for the framework annotation of the same binary name. */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE})
@MessageHandler
public @interface CommandHandler {}
