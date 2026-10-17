package dev.otherlode.instrumentation.endpoints.micronaut3;

import io.micronaut.http.annotation.Controller;

@Controller("/diamond")
public class DiamondController extends DiamondSuper implements DiamondTop {}
