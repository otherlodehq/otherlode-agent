package com.example.target.inherit;

import org.springframework.jms.annotation.JmsListener;

public interface JmsApi {
    @JmsListener
    void onMessage();

    @ComposedJms
    void composed();
}
