package dev.otherlode.instrumentation.endpoints.micronaut3;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Error;

@Controller
public class StatusController {
    @Error(status = HttpStatus.NOT_FOUND, global = true)
    public HttpResponse<String> notFound() {
        return HttpResponse.notFound("none");
    }

    @Error(status = HttpStatus.METHOD_NOT_ALLOWED, global = true)
    public HttpResponse<String> notAllowed() {
        return HttpResponse.status(HttpStatus.METHOD_NOT_ALLOWED);
    }
}
