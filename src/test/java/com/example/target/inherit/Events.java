package com.example.target.inherit;

import org.springframework.context.event.EventListener;

/** An interface from another family, beside the JAX-RS one. */
public interface Events {
    @EventListener
    String mixed();
}
