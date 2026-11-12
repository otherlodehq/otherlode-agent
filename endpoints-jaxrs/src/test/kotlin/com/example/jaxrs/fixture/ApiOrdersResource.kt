package com.example.jaxrs.fixture

import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces

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
