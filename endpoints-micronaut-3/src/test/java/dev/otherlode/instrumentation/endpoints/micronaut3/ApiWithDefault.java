package dev.otherlode.instrumentation.endpoints.micronaut3;

import io.micronaut.http.annotation.Get;

public interface ApiWithDefault {
    @Get("/declared")
    String declared();

    @Get("/defaulted")
    default String defaulted() {
        return "defaulted";
    }
}
