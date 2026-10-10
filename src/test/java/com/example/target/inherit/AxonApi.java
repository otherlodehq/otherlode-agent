package com.example.target.inherit;

import org.axonframework.eventhandling.EventHandler;
import org.axonframework.lifecycle.StartHandler;

/** An interface whose methods carry Axon 4 annotations. */
public interface AxonApi {
    @EventHandler
    void event();

    @StartHandler
    void start();
}
