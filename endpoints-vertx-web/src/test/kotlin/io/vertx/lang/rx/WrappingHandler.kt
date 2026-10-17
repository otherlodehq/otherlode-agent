package io.vertx.lang.rx

import io.vertx.core.Handler
import io.vertx.ext.web.RoutingContext

/** Stands in for a library's wrapper around an application's handler, in a package of RxJava for Vert.x. */
class WrappingHandler(
    private val inner: Handler<RoutingContext>,
) : Handler<RoutingContext> {
    override fun handle(context: RoutingContext) = inner.handle(context)
}
