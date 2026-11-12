package com.example.target.inherit;

import com.example.outside.named.ClassCall;
import com.example.outside.named.RuntimeCall;

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
