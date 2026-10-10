package org.axonframework.modelling.saga;

import java.lang.annotation.*;
import org.axonframework.eventhandling.EventHandler;

/** A test stand-in for the framework annotation of the same binary name. */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE})
@EventHandler
public @interface SagaEventHandler {}
