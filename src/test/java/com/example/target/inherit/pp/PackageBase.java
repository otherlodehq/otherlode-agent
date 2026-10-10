package com.example.target.inherit.pp;

import org.springframework.context.event.EventListener;

/** A public class with a package-private annotated method. */
public class PackageBase {
    @EventListener
    void quiet() {}
}
