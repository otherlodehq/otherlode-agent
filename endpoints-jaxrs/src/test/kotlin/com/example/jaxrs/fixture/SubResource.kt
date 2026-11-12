package com.example.jaxrs.fixture

import jakarta.ws.rs.GET
import jakarta.ws.rs.Path

@Path("/sub")
class SubResource {
    @GET
    @Path("/leaf")
    fun leaf(): String = "leaf"
}
