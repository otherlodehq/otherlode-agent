package com.example.target.inherit;

import org.axonframework.commandhandling.CommandHandler;

/** Carries an Axon annotation on a method whose interface carries a Temporal one. */
public class AxonOverTemporal implements WfLooseApi {
    @CommandHandler
    @Override
    public void poke() {}
}
