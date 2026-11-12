package com.example.target.inherit;

import io.temporal.activity.ActivityInterface;
import org.springframework.context.event.EventListener;

@ActivityInterface
public interface ActAnnotatedApi {
    @EventListener
    String both();
}
