package com.example.target.inherit;

import org.springframework.scheduling.annotation.Scheduled;

/** Carries its own callback annotation on a method whose interface method carries another. */
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
