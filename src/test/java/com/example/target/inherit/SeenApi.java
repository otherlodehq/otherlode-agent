package com.example.target.inherit;

import com.example.outside.named.RuntimeCall;

/** An interface whose only annotated method is overridden by a method with a label of its own. */
public interface SeenApi {
    @RuntimeCall
    void m();
}
