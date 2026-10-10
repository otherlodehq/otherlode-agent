package com.example.target.inherit;

import org.springframework.scheduling.annotation.Scheduled;

/** Labelled by its own annotation, over a supertype method that carries a named one. */
public class SeenOnly implements SeenApi {
    @Scheduled
    @Override
    public void m() {}
}
