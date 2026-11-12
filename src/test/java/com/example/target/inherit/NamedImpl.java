package com.example.target.inherit;

import org.springframework.scheduling.annotation.Scheduled;

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
