package dev.otherlode.instrumentation.endpoints.vertxweb

import io.vertx.core.Handler
import io.vertx.ext.web.RoutingContext

class NamedHandler : Handler<RoutingContext> {
    override fun handle(ctx: RoutingContext) {
        ctx.response().end("named")
    }
}

class PassHandler : Handler<RoutingContext> {
    override fun handle(ctx: RoutingContext) {
        ctx.next()
    }
}

fun referencedHandler(ctx: RoutingContext) {
    ctx.response().end("referenced")
}
