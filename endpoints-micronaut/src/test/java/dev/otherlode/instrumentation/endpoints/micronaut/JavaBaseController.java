package dev.otherlode.instrumentation.endpoints.micronaut;

import io.micronaut.http.annotation.Get;

public abstract class JavaBaseController {
    @Get("/inherited")
    public String inherited() {
        return "inherited";
    }
}
