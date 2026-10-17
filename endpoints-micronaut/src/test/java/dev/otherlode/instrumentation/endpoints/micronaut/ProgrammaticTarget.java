package dev.otherlode.instrumentation.endpoints.micronaut;

import io.micronaut.context.annotation.Executable;
import jakarta.inject.Singleton;

@Singleton
public class ProgrammaticTarget {
    @Executable
    public String handle() {
        return "programmatic";
    }
}
