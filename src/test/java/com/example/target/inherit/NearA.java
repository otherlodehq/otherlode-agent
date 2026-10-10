package com.example.target.inherit;

import org.springframework.web.bind.annotation.GetMapping;

/** The first interface in the order tests. */
public interface NearA {
    @GetMapping
    void m();
}
