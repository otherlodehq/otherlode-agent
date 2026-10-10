package com.example.target.inherit;

/** Implements the activity interface. */
public class ActImpl implements ActApi {
    @Override
    public String plain() {
        return "";
    }

    @Override
    public String withBody() {
        return "";
    }

    public String notInInterface() {
        return "";
    }
}
