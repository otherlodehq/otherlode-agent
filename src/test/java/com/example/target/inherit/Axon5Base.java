package com.example.target.inherit;

import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;

/** Superclass methods that carry Axon 5 annotations. */
public class Axon5Base {
    @CommandHandler
    public void command() {}

    @EntityCreator
    public void creator() {}
}
