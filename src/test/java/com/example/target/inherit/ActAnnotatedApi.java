package com.example.target.inherit;

import io.temporal.activity.ActivityInterface;
import org.springframework.context.event.EventListener;

/** An activity interface with a method that also carries a callback annotation. */
@ActivityInterface
public interface ActAnnotatedApi {
    @EventListener
    String both();
}
