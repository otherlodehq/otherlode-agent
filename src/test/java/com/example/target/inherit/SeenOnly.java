package com.example.target.inherit;

import org.springframework.scheduling.annotation.Scheduled;

public class SeenOnly implements SeenApi {
    @Scheduled
    @Override
    public void m() {}
}
