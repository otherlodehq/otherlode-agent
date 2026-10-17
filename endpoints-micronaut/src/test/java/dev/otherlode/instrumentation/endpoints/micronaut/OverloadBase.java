package dev.otherlode.instrumentation.endpoints.micronaut;

import io.micronaut.http.annotation.Get;

public abstract class OverloadBase {
    @Get("/list/{name}")
    public String list(String name) {
        return "base";
    }
}
