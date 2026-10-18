package io.ktor.server.servlet

import jakarta.servlet.http.HttpServlet

/** Stands in for Ktor's servlet engine, which the module leaves out by its name where a Ktor module covers the release. */
open class ServletApplicationEngine : HttpServlet()
