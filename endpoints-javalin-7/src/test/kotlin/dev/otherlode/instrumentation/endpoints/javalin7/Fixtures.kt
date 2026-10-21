package dev.otherlode.instrumentation.endpoints.javalin7

import io.javalin.Javalin
import io.javalin.apibuilder.CrudHandler
import io.javalin.config.JavalinConfig
import io.javalin.config.RoutesConfig
import io.javalin.http.Context
import io.javalin.http.Handler

/** A handler that is a class of its own. */
class NamedHandler : Handler {
    override fun handle(ctx: Context) {
        ctx.result("named")
    }
}

/** A handler passed by reference. */
fun referencedHandler(ctx: Context) {
    ctx.result("referenced")
}

/** A `CrudHandler` whose five methods answer with their own name. */
class UserCrud : CrudHandler {
    override fun getAll(ctx: Context) {
        ctx.result("getAll")
    }

    override fun getOne(
        ctx: Context,
        resourceId: String,
    ) {
        ctx.result("getOne $resourceId")
    }

    override fun create(ctx: Context) {
        ctx.result("create")
    }

    override fun update(
        ctx: Context,
        resourceId: String,
    ) {
        ctx.result("update $resourceId")
    }

    override fun delete(
        ctx: Context,
        resourceId: String,
    ) {
        ctx.result("delete $resourceId")
    }
}

/** Starts a server on a free port with [configure] and then [routes] applied, runs [block] with its port and stops it. */
fun <T> serve(
    configure: (JavalinConfig) -> Unit = {},
    routes: (RoutesConfig) -> Unit = {},
    block: (Int) -> T,
): T {
    val app =
        Javalin.create {
            configure(it)
            routes(it.routes)
        }
    app.start("127.0.0.1", 0)
    try {
        return block(app.port())
    } finally {
        app.stop()
    }
}
