package com.example.servlet.fixture

import jakarta.servlet.GenericServlet
import jakarta.servlet.Servlet
import jakarta.servlet.ServletConfig
import jakarta.servlet.ServletException
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse

private fun HttpServletResponse.say(text: String) {
    contentType = "text/plain"
    writer.write(text)
}

/** Declares `GET` and `POST`. */
open class GetPostServlet : HttpServlet() {
    override fun doGet(
        req: HttpServletRequest,
        resp: HttpServletResponse,
    ) = resp.say("get")

    override fun doPost(
        req: HttpServletRequest,
        resp: HttpServletResponse,
    ) = resp.say("post")
}

/** Declares every `do*` method but `doPatch`. */
class AllVerbsServlet : HttpServlet() {
    override fun doGet(
        req: HttpServletRequest,
        resp: HttpServletResponse,
    ) = resp.say("get")

    override fun doPost(
        req: HttpServletRequest,
        resp: HttpServletResponse,
    ) = resp.say("post")

    override fun doPut(
        req: HttpServletRequest,
        resp: HttpServletResponse,
    ) = resp.say("put")

    override fun doDelete(
        req: HttpServletRequest,
        resp: HttpServletResponse,
    ) = resp.say("delete")

    override fun doOptions(
        req: HttpServletRequest,
        resp: HttpServletResponse,
    ) = resp.say("options")

    override fun doTrace(
        req: HttpServletRequest,
        resp: HttpServletResponse,
    ) = resp.say("trace")
}

/** Declares `GET` and `HEAD`, so `HEAD` is a verb of its own. */
class GetAndHeadServlet : HttpServlet() {
    override fun doGet(
        req: HttpServletRequest,
        resp: HttpServletResponse,
    ) = resp.say("get")

    override fun doHead(
        req: HttpServletRequest,
        resp: HttpServletResponse,
    ) {
        resp.setHeader("X-Head", "own")
    }
}

/** Declares `GET` in a superclass and `POST` here. */
open class BaseGetServlet : HttpServlet() {
    override fun doGet(
        req: HttpServletRequest,
        resp: HttpServletResponse,
    ) = resp.say("get")
}

class InheritingServlet : BaseGetServlet() {
    override fun doPost(
        req: HttpServletRequest,
        resp: HttpServletResponse,
    ) = resp.say("post")
}

/** Overrides the protected `service`, so it serves every verb. */
class ProtectedServiceServlet : HttpServlet() {
    override fun service(
        req: HttpServletRequest,
        resp: HttpServletResponse,
    ) = resp.say("service")
}

/** Overrides the public `service` and calls `super`, which reaches the protected one and `doGet`. */
class PublicServiceSuperServlet : HttpServlet() {
    override fun service(
        req: ServletRequest,
        res: ServletResponse,
    ) {
        super.service(req, res)
    }

    override fun doGet(
        req: HttpServletRequest,
        resp: HttpServletResponse,
    ) = resp.say("get")
}

/** Overrides the protected `service` and calls `super`. */
class ProtectedServiceSuperServlet : HttpServlet() {
    override fun service(
        req: HttpServletRequest,
        resp: HttpServletResponse,
    ) {
        super.service(req, resp)
    }

    override fun doGet(
        req: HttpServletRequest,
        resp: HttpServletResponse,
    ) = resp.say("get")
}

/** A subclass of one that overrides the public `service`, which overrides it again and calls `super`. */
open class OuterServiceServlet : PublicServiceBase() {
    override fun service(
        req: ServletRequest,
        res: ServletResponse,
    ) {
        super.service(req, res)
    }
}

open class PublicServiceBase : HttpServlet() {
    override fun service(
        req: ServletRequest,
        res: ServletResponse,
    ) {
        super.service(req, res)
    }

    override fun doGet(
        req: HttpServletRequest,
        resp: HttpServletResponse,
    ) = resp.say("get")
}

/** Not an `HttpServlet`. */
class GenericOnlyServlet : GenericServlet() {
    override fun service(
        req: ServletRequest,
        res: ServletResponse,
    ) {
        res.contentType = "text/plain"
        res.writer.write("generic")
    }
}

/** A bare `Servlet`. */
class BareServlet : Servlet {
    private var config: ServletConfig? = null

    override fun init(config: ServletConfig) {
        this.config = config
    }

    override fun getServletConfig(): ServletConfig? = config

    override fun service(
        req: ServletRequest,
        res: ServletResponse,
    ) {
        res.contentType = "text/plain"
        res.writer.write("bare")
    }

    override fun getServletInfo(): String = "bare"

    override fun destroy() {}
}

/** Forwards to [target]. */
class ForwardingServlet(
    private val target: String,
) : HttpServlet() {
    override fun doGet(
        req: HttpServletRequest,
        resp: HttpServletResponse,
    ) {
        req.getRequestDispatcher(target).forward(req, resp)
    }
}

/** Includes [target] and then writes. */
class IncludingServlet(
    private val target: String,
) : HttpServlet() {
    override fun doGet(
        req: HttpServletRequest,
        resp: HttpServletResponse,
    ) {
        req.getRequestDispatcher(target).include(req, resp)
        resp.say("included")
    }
}

/** Fails, so the container dispatches to its error page. */
class FailingServlet : HttpServlet() {
    override fun doGet(
        req: HttpServletRequest,
        resp: HttpServletResponse,
    ): Unit = throw ServletException("boom")
}

/** Answers an error page dispatch, and records the dispatcher type the container showed it. */
class ErrorPageServlet : HttpServlet() {
    override fun doGet(
        req: HttpServletRequest,
        resp: HttpServletResponse,
    ) {
        dispatches += req.dispatcherType.name
        resp.say("error page")
    }

    companion object {
        val dispatches: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()
    }
}

/** A subclass of a servlet the module leaves out by its hierarchy. */
class SubOfOwnedServlet : io.javalin.http.servlet.JavalinServlet()

/** Calls [target]'s public `service` directly, as a servlet that delegates to another does. */
class DelegatingServlet(
    private val target: HttpServlet,
) : HttpServlet() {
    override fun doGet(
        req: HttpServletRequest,
        resp: HttpServletResponse,
    ) {
        val request: ServletRequest = req
        val response: ServletResponse = resp
        target.service(request, response)
    }
}

/** Starts asynchronous processing and dispatches to [target]. */
class AsyncDispatchingServlet(
    private val target: String,
) : HttpServlet() {
    override fun doGet(
        req: HttpServletRequest,
        resp: HttpServletResponse,
    ) {
        req.startAsync().dispatch(target)
    }
}
