package com.example.target.inherit;

import org.springframework.scheduling.annotation.Scheduled;

public class OwnWins implements InheritApi {
    @Scheduled
    @Override
    public String fromInterface() {
        return "";
    }

    @Override
    public Result openApi() {
        return new Result();
    }

    @Override
    public void scheduledInterface() {}

    @Override
    public Object beanAbstract() {
        return null;
    }
}
