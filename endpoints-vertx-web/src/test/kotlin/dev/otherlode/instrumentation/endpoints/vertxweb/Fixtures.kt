package dev.otherlode.instrumentation.endpoints.vertxweb

import io.vertx.core.Handler
import io.vertx.ext.web.RoutingContext

/** A handler that is a class of its own. */
class NamedHandler : Handler<RoutingContext> {
    override fun handle(ctx: RoutingContext) {
        ctx.response().end("named")
    }
}

/** A handler that passes the request on. */
class PassHandler : Handler<RoutingContext> {
    override fun handle(ctx: RoutingContext) {
        ctx.next()
    }
}

/** A handler passed by reference. */
fun referencedHandler(ctx: RoutingContext) {
    ctx.response().end("referenced")
}
