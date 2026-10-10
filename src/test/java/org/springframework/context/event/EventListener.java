package org.springframework.context.event;

import java.lang.annotation.*;

/**
 * A test stand-in for the framework annotation of the same binary name. It carries a runtime
 * annotation, as Spring's carries {@code @Reflective}.
 */
@com.example.outside.named.FrameworkCarried
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface EventListener {}
