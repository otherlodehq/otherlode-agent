package com.example.target.named;

import org.springframework.context.event.EventListener;

/** Has class retention and carries a built-in callback annotation, which Spring could not see at run time. */
@EventListener
public @interface ClassComposedOfBuiltIn {}
