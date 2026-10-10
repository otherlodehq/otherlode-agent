package com.example.target.inherit;

/** Reaches the activity interface only through its superclass. */
public class ActViaSuper extends ActBaseImpl {
    @Override
    public String plain() {
        return "";
    }
}
