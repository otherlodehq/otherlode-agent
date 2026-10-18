package com.example.otelbridge

import jakarta.ws.rs.GET
import jakarta.ws.rs.Path

@Path("users")
class UsersResource {
    @GET
    fun users(): String = "users"
}
