package com.example.target.inherit;

import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;

public interface InheritApi {
    @GetMapping
    String fromInterface();

    @GetMapping
    Result openApi();

    @EventListener
    default void fromDefault() {}

    @Scheduled
    void scheduledInterface();

    @Bean
    Object beanAbstract();

    @Bean
    default Object beanDefault() {
        return null;
    }
}
