package com.example.target.inherit;

import org.axonframework.lifecycle.StartHandler;

/** A superclass method that carries a start handler, which does not pass down. */
public class AxonStartBase {
    @StartHandler
    public void start() {}
}
