package dev.otherlode.instrumentation.endpoints.micronaut3;

import io.micronaut.http.annotation.Controller;

@Controller("/generic")
public class GenericController extends GenericBase<String> {}
