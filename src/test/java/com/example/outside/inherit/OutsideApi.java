package com.example.outside.inherit;

import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;

/** An interface outside the instrumented packages whose methods carry callback annotations. */
public interface OutsideApi {
    @EventListener
    void listener();

    @Scheduled
    void scheduled();

    void plain();
}
