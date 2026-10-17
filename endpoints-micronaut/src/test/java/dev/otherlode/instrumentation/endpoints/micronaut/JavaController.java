package dev.otherlode.instrumentation.endpoints.micronaut;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Consumes;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Head;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;

@Controller("/java")
public class JavaController extends JavaBaseController {
    @Get("/hello")
    public String hello() {
        return "hello";
    }

    @Post("/echo")
    @Consumes(MediaType.TEXT_PLAIN)
    public String echo(@Body String body) {
        return body;
    }

    @Get("/items/{id}")
    public String item(@PathVariable String id) {
        return id;
    }

    @Get("/colon/{id:?}")
    public String colon(@Nullable @PathVariable String id) {
        return String.valueOf(id);
    }

    @Get("/segment{/id}")
    public String segment(@Nullable @PathVariable String id) {
        return String.valueOf(id);
    }

    @Get("/files/{+path}")
    public String files(@PathVariable String path) {
        return path;
    }

    @Head("/probe")
    public HttpResponse<?> probe() {
        return HttpResponse.ok();
    }

    @Get(value = "/nohead", headRoute = false)
    public String noHead() {
        return "nohead";
    }

    @Get("/primitives/{id}")
    public int[] primitives(@PathVariable long id, boolean flag, String[] names) {
        return new int[] {1};
    }

    @Get("/generic/{id}")
    public <T extends Number> java.util.List<T> generic(@PathVariable T id, java.util.List<String> tags) {
        return java.util.List.of(id);
    }
}
