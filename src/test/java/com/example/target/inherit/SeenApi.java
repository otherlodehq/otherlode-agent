package com.example.target.inherit;

import com.example.outside.named.RuntimeCall;

public interface SeenApi {
    @RuntimeCall
    void m();
}
