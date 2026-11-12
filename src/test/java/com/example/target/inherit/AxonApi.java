package com.example.target.inherit;

import org.axonframework.eventhandling.EventHandler;
import org.axonframework.lifecycle.StartHandler;

public interface AxonApi {
    @EventHandler
    void event();

    @StartHandler
    void start();
}
