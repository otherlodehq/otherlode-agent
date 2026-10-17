package dev.otherlode.instrumentation.endpoints.micronaut3;

import io.micronaut.context.annotation.Executable;
import io.micronaut.http.annotation.Get;
import jakarta.inject.Singleton;

@Singleton
public class HeadTarget {
    @Get("/never-routed")
    @Executable
    public String both() {
        return "both";
    }
}
