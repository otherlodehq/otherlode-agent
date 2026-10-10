package com.example.target.named;

import com.example.outside.named.RuntimeCall;

/** Carries a named annotation on the class, which marks none of its methods. */
@RuntimeCall
public class OnTheClass {
    public void method() {}
}
