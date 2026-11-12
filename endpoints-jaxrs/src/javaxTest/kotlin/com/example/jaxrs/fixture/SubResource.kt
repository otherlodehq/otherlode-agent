package com.example.jaxrs.fixture

import javax.ws.rs.GET
import javax.ws.rs.Path

@Path("/sub")
class SubResource {
    @GET
    @Path("/leaf")
    fun leaf(): String = "leaf"
}
