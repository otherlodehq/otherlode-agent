package com.example.target.inherit;

/** Overrides a method that carries a callback annotation on an activity interface. */
public class ActAnnotatedImpl implements ActAnnotatedApi {
    @Override
    public String both() {
        return "";
    }
}
