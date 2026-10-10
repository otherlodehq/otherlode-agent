package io.temporal.nexus;

import java.lang.annotation.*;

/**
 * A test stand-in for the framework annotation of the same binary name. It also targets annotation
 * types, which the real one does not, so a fixture can compose it.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE})
public @interface TemporalOperation {}
