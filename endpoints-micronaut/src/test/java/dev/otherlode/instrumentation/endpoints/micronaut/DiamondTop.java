package dev.otherlode.instrumentation.endpoints.micronaut;

import io.micronaut.http.annotation.Get;

public interface DiamondTop {
    @Get("/m")
    default String m() {
        return "top";
    }
}
