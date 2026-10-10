package com.example.target.inherit;

/** Implements methods declared at three levels of an activity interface. */
public class ActChildImpl implements ActChildApi {
    @Override
    public String grand() {
        return "";
    }

    @Override
    public String parent() {
        return "";
    }

    @Override
    public String child() {
        return "";
    }
}
