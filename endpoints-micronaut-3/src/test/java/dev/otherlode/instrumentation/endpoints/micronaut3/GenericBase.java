package dev.otherlode.instrumentation.endpoints.micronaut3;

import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Post;

public abstract class GenericBase<T extends CharSequence> {
    @Post("/save")
    public T save(@Body T value) {
        return value;
    }
}
