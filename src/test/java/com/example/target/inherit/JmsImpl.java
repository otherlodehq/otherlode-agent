package com.example.target.inherit;

/** Overrides an interface method whose {@code @JmsListener} carries an annotation that would pass down. */
public class JmsImpl implements JmsApi {
    @Override
    public void onMessage() {}

    @Override
    public void composed() {}
}
