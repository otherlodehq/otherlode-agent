package com.example.target.inherit;

import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;

/** Implements a JAX-RS interface with and without JAX-RS annotations of its own. */
public class RestImpl implements RestApi, Events {
    @Override
    public String hello() {
        return "";
    }

    @Produces
    @Override
    public String withProduces() {
        return "";
    }

    @Override
    public String withParam(@PathParam("id") String id) {
        return "";
    }

    @Produces
    @Override
    public String mixed() {
        return "";
    }
}
