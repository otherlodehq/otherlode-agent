package com.example.target.inherit;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

/** A JAX-RS resource interface. */
public interface RestApi {
    @GET
    @Path("hello")
    String hello();

    @GET
    String withProduces();

    @GET
    String withParam(String id);

    @GET
    String mixed();
}
