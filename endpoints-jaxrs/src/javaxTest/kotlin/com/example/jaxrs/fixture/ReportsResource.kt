package com.example.jaxrs.fixture

import javax.ws.rs.GET
import javax.ws.rs.Path

abstract class BaseReports {
    @GET
    @Path("/summary")
    abstract fun summary(): String
}

@Path("/reports")
class ReportsResource : BaseReports() {
    override fun summary(): String = "summary"
}
