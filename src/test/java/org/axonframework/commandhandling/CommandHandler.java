package org.axonframework.commandhandling;

import java.lang.annotation.*;
import org.axonframework.messaging.annotation.MessageHandler;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE})
@MessageHandler
public @interface CommandHandler {}
