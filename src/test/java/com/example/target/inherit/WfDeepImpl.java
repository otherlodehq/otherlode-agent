package com.example.target.inherit;

/** Reaches a signal two interfaces up and overrides a default method. */
public class WfDeepImpl implements WfDeepApi {
    @Override
    public void poke() {}

    @Override
    public String peek() {
        return "";
    }
}
