package io.vertx.lang.rx

import io.vertx.core.Handler
import io.vertx.ext.web.RoutingContext

class WrappingHandler(
    private val inner: Handler<RoutingContext>,
) : Handler<RoutingContext> {
    override fun handle(context: RoutingContext) = inner.handle(context)
}
