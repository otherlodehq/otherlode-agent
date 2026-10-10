package com.example.target.inherit;

import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;

/** Axon 5 annotations on the method itself. */
public class Axon5Impl {
    @CommandHandler
    public void command() {}

    @EntityCreator
    public void creator() {}
}
