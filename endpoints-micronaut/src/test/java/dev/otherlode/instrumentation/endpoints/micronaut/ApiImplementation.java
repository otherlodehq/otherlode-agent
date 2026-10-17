package dev.otherlode.instrumentation.endpoints.micronaut;

import io.micronaut.http.annotation.Controller;

@Controller("/api")
public class ApiImplementation implements ApiWithDefault {
    @Override
    public String declared() {
        return "declared";
    }
}
