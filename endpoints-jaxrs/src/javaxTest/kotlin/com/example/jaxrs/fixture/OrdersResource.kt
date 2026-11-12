package com.example.jaxrs.fixture

import javax.ws.rs.DELETE
import javax.ws.rs.GET
import javax.ws.rs.HttpMethod
import javax.ws.rs.POST
import javax.ws.rs.Path
import javax.ws.rs.PathParam

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@HttpMethod("PURGE")
annotation class PURGE

@Path("/orders")
class OrdersResource {
    @GET
    @Path("/{id}")
    fun getOrder(
        @PathParam("id") id: String,
    ): String = "order-$id"

    @POST
    fun createOrder(): String = "created"

    @GET
    @Path("/{id}/invoice")
    fun getInvoice(
        @PathParam("id") id: String,
    ): String = "invoice-$id"

    @DELETE
    @Path("/{id: \\d+}")
    fun deleteOrder(
        @PathParam("id") id: String,
    ): String = "deleted-$id"

    @Path("/sub")
    fun subResource(): SubResource = SubResource()

    @PURGE
    @Path("/{id}/purge")
    fun purgeOrder(
        @PathParam("id") id: String,
    ): String = "purged-$id"
}
