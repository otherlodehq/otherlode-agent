package com.example.target.inherit;

import org.axonframework.commandhandling.CommandHandler;

public class AxonOverTemporal implements WfLooseApi {
    @CommandHandler
    @Override
    public void poke() {}
}
