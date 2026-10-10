package com.example.target.inherit;

import com.example.outside.inherit.ActOutsideApi;

/** Overrides a method of an out-of-scope activity interface. */
public class ActOutsideImpl implements ActOutsideApi {
    @Override
    public String outside() {
        return "";
    }
}
