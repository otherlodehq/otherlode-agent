package com.example.target.inherit;

import org.springframework.context.event.EventListener;

/** A generic interface, so its implementation is reached through a bridge. */
public interface GenericApi<T> {
    @EventListener
    void handle(T event);
}
