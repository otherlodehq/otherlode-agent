package dev.otherlode.instrumentation.endpoints.micronaut3;

import io.micronaut.http.annotation.Controller;

@Controller("/overload")
public class OverloadController extends OverloadBase {
    public String list(int count) {
        return "count";
    }

    private static String list(Integer boxed) {
        return "boxed";
    }
}
