package dev.otherlode.instrumentation.endpoints.micronaut;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Error;
import io.micronaut.http.annotation.Get;

@Controller("/boom")
public class ErrorController {
    @Get("/fail")
    public String fail() {
        throw new IllegalStateException("boom");
    }

    @Error(exception = IllegalStateException.class)
    public HttpResponse<String> onFailure() {
        return HttpResponse.ok("handled");
    }
}
