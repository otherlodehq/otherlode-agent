package com.example.target.inherit;

import com.example.outside.named.ClassCall;
import com.example.outside.named.RuntimeCall;

/** An interface whose methods carry an adopter's own annotations at each retention. */
public interface NamedApi {
    @RuntimeCall
    void runtime();

    @ClassCall
    void classRetained();

    @RuntimeCall
    default void withDefault() {}

    @RuntimeCall
    default void defaultOnly() {}
}
