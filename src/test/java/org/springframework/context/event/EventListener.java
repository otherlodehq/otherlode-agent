package org.springframework.context.event;

import java.lang.annotation.*;

@com.example.outside.named.FrameworkCarried
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface EventListener {}
