package com.example.target.inherit;

import com.example.outside.inherit.OutsideApi;

/** Overrides out-of-scope methods, some with annotations. */
public class OutsideImpl implements OutsideApi {
    @Override
    public void listener() {}

    @Override
    public void scheduled() {}

    @Override
    public void plain() {}
}
