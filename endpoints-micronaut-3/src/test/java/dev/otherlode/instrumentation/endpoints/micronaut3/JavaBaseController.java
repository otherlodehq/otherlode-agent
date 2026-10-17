package dev.otherlode.instrumentation.endpoints.micronaut3;

import io.micronaut.http.annotation.Get;

public abstract class JavaBaseController {
    @Get("/inherited")
    public String inherited() {
        return "inherited";
    }
}
