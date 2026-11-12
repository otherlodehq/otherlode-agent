package com.example.jaxrs.fixture

import javax.ws.rs.GET
import javax.ws.rs.POST
import javax.ws.rs.Path
import javax.ws.rs.PathParam
import javax.ws.rs.Produces

@Path("/api/orders")
interface ApiOrders {
    @GET
    @Path("/{id}")
    fun get(
        @PathParam("id") id: String,
    ): String

    @POST
    fun create(): String

    @GET
    @Path("/{id}/audit")
    fun audit(
        @PathParam("id") id: String,
    ): String
}

class ApiOrdersResource : ApiOrders {
    override fun get(id: String): String = "order-$id"

    override fun create(): String = "created"

    @Produces("text/plain")
    override fun audit(id: String): String = "audit-$id"
}
