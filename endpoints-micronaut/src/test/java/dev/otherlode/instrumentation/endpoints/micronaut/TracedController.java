package dev.otherlode.instrumentation.endpoints.micronaut;

import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;

@Traced
@Controller("/traced")
public class TracedController extends JavaBaseController {
    @Get("/own")
    public String own() {
        return "own";
    }
}
