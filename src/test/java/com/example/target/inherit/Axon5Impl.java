package com.example.target.inherit;

import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;

public class Axon5Impl {
    @CommandHandler
    public void command() {}

    @EntityCreator
    public void creator() {}
}
