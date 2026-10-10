package org.axonframework.eventsourcing.annotation.reflection;

import java.lang.annotation.*;

/** A test stand-in for the framework annotation of the same binary name. */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE})
public @interface EntityCreator {}
