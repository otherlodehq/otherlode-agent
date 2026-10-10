package com.example.target.inherit;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;

/** A superclass whose methods carry callback annotations. */
public class InheritBase {
    @EventListener
    public void fromSuperclass() {}

    @Scheduled
    public void scheduledSuper() {}

    @PostConstruct
    public void postConstructSuper() {}

    @Bean
    public Object beanSuper() {
        return null;
    }
}
