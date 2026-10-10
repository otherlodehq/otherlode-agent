package com.example.target.inherit;

/** Implements the workflow interface with no annotation on any method. */
public class WfImpl implements WfApi {
    @Override
    public String run() {
        return "";
    }

    @Override
    public void signal() {}

    @Override
    public String query() {
        return "";
    }

    @Override
    public void update() {}

    @Override
    public void validateUpdate() {}
}
