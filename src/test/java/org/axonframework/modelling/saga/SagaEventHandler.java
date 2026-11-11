package org.axonframework.modelling.saga;

import java.lang.annotation.*;
import org.axonframework.eventhandling.EventHandler;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE})
@EventHandler
public @interface SagaEventHandler {}
