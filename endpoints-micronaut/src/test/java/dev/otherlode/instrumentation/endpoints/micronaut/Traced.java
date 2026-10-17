package dev.otherlode.instrumentation.endpoints.micronaut;

import io.micronaut.aop.Around;
import io.micronaut.context.annotation.Type;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Around
@Type(TracedInterceptor.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface Traced {}
