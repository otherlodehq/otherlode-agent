package io.javalin.http.servlet

import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse

/** Stands in for Javalin's servlet, which the module leaves out by its name. */
open class JavalinServlet : HttpServlet() {
    override fun doGet(
        req: HttpServletRequest,
        resp: HttpServletResponse,
    ) {
        resp.writer.write("javalin")
    }
}
