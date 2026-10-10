package com.example.target.inherit;

import org.springframework.scheduling.annotation.Scheduled;

/** A superclass method that matches the key and carries nothing that passes down. */
public class SkipBase {
    @Scheduled
    public void m() {}
}
