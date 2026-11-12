package com.example.jaxrs.fixture

import jakarta.ws.rs.GET
import jakarta.ws.rs.Path

abstract class BaseReports {
    @GET
    @Path("/summary")
    abstract fun summary(): String
}

@Path("/reports")
class ReportsResource : BaseReports() {
    override fun summary(): String = "summary"
}
