package com.example.target.inherit;

import org.axonframework.commandhandling.CommandHandler;
import org.axonframework.lifecycle.StartHandler;
import org.axonframework.modelling.saga.SagaEventHandler;

/** Axon 4 handler annotations on the method itself. */
public class AxonImpl {
    @CommandHandler
    public void command() {}

    @SagaEventHandler
    public void saga() {}

    @ComposedCommand
    public void composed() {}

    @StartHandler
    public void start() {}
}
