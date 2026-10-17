package dev.otherlode.instrumentation.endpoints.micronaut;

import io.micronaut.http.annotation.Controller;

@Controller("/diamond")
public class DiamondController extends DiamondSuper implements DiamondTop {}
