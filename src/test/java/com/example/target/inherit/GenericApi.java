package com.example.target.inherit;

import org.springframework.context.event.EventListener;

public interface GenericApi<T> {
    @EventListener
    void handle(T event);
}
