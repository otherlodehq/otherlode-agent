package com.example.target.inherit;

import com.example.outside.named.RuntimeCall;

/** A superclass method that carries an adopter's annotation. */
public class NamedBase {
    @RuntimeCall
    public void fromSuperclass() {}
}
