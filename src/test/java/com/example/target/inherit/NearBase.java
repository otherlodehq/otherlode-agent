package com.example.target.inherit;

import org.springframework.context.event.EventListener;

/** A superclass method that carries an inheritable annotation. */
public class NearBase {
    @EventListener
    public void m() {}
}
