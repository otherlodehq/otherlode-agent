package dev.otherlode.instrumentation.endpoints.micronaut;

import io.micronaut.http.annotation.Controller;

@Controller("/generic")
public class GenericController extends GenericBase<String> {}
