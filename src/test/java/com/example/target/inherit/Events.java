package com.example.target.inherit;

import org.springframework.context.event.EventListener;

public interface Events {
    @EventListener
    String mixed();
}
