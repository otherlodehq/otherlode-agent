package com.example.target.inherit;

import org.springframework.scheduling.annotation.Scheduled;

/** Implements and overrides annotated methods without repeating the annotation, except where noted. */
public class NamedImpl extends NamedBase implements NamedApi {
    @Override
    public void runtime() {}

    @Override
    public void classRetained() {}

    @Scheduled
    @Override
    public void withDefault() {}

    @Override
    public void defaultOnly() {}

    @Override
    public void fromSuperclass() {}
}
