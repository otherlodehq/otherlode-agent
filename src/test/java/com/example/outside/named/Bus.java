package com.example.outside.named;

import java.lang.annotation.*;

public class Bus {
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Handler {}
}
