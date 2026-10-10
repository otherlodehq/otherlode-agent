package com.example.target.inherit;

import org.springframework.jms.annotation.JmsListener;

/** An interface method carrying a listener annotation that never passes down. */
public interface JmsApi {
    @JmsListener
    void onMessage();

    @ComposedJms
    void composed();
}
