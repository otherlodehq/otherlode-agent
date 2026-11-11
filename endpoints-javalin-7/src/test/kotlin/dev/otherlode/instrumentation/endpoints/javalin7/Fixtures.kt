package dev.otherlode.instrumentation.endpoints.javalin7

import io.javalin.Javalin
import io.javalin.apibuilder.CrudHandler
import io.javalin.config.JavalinConfig
import io.javalin.config.RoutesConfig
import io.javalin.http.Context
import io.javalin.http.Handler

class NamedHandler : Handler {
    override fun handle(ctx: Context) {
        ctx.result("named")
    }
}

fun referencedHandler(ctx: Context) {
    ctx.result("referenced")
}

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
