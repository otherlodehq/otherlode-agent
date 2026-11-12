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

class ProtectedServiceServlet : HttpServlet() {
    override fun service(
        req: HttpServletRequest,
        resp: HttpServletResponse,
    ) = resp.say("service")
}

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

class GenericOnlyServlet : GenericServlet() {
    override fun service(
        req: ServletRequest,
        res: ServletResponse,
    ) {
        res.contentType = "text/plain"
        res.writer.write("generic")
    }
}

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

class NamedDispatchingServlet(
    private val target: String,
) : HttpServlet() {
    override fun doGet(
        req: HttpServletRequest,
        resp: HttpServletResponse,
    ) {
        servletContext.getNamedDispatcher(target).include(req, resp)
        servletContext.getNamedDispatcher(target).forward(req, resp)
    }
}

class FailingServlet : HttpServlet() {
    override fun doGet(
        req: HttpServletRequest,
        resp: HttpServletResponse,
    ): Unit = throw ServletException("boom")
}

class FailingPostServlet : HttpServlet() {
    override fun doPost(
        req: HttpServletRequest,
        resp: HttpServletResponse,
    ): Unit = throw ServletException("boom")
}

object ErrorPageMethods {
    val seen: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()
}

class GetOnlyErrorPageServlet : HttpServlet() {
    override fun doGet(
        req: HttpServletRequest,
        resp: HttpServletResponse,
    ) {
        ErrorPageMethods.seen += "GET"
        resp.say("error page")
    }
}

class GetPostErrorPageServlet : HttpServlet() {
    override fun doGet(
        req: HttpServletRequest,
        resp: HttpServletResponse,
    ) {
        ErrorPageMethods.seen += "GET"
        resp.say("error page")
    }

    override fun doPost(
        req: HttpServletRequest,
        resp: HttpServletResponse,
    ) {
        ErrorPageMethods.seen += "POST"
        resp.say("error page")
    }
}

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

class SubOfOwnedServlet : io.javalin.http.servlet.JavalinServlet()

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

class SelfDispatchingServlet : HttpServlet() {
    override fun doGet(
        req: HttpServletRequest,
        resp: HttpServletResponse,
    ) {
        if (req.dispatcherType == jakarta.servlet.DispatcherType.ASYNC) {
            resp.status = 200
        } else {
            req.startAsync().dispatch()
        }
    }
}

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
