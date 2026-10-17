package dev.otherlode.instrumentation.endpoints.javalin6

import dev.otherlode.export.EndpointDiscoverySource
import dev.otherlode.registry.EndpointRegistry
import io.javalin.apibuilder.ApiBuilder
import io.javalin.http.UnauthorizedResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val HANDLE_DESCRIPTOR = "(Lio/javalin/http/Context;)V"
private const val ITEM_DESCRIPTOR = "(Lio/javalin/http/Context;Ljava/lang/String;)V"

/**
 * Proves [Javalin6Module] end to end against a real Javalin server, with [Javalin6Module] and the
 * Javalin 7 module both installed. The same sources run against every tested 6.x release.
 */
class Javalin6ModuleTest {
    @Test
    fun `routes are declared at registration and counted once at dispatch`() {
        withModules { registry ->
            serve(
                configure = { cfg ->
                    cfg.router.apiBuilder {
                        ApiBuilder.path("/api") {
                            ApiBuilder.path("/v1") {
                                ApiBuilder.get("/items/{id}") { ctx -> ctx.result("item") }
                            }
                        }
                    }
                },
                routes = { app ->
                    app.before { }
                    app.after { }
                    app.beforeMatched { }
                    app.afterMatched { }
                    app.get("/hello") { ctx -> ctx.result("hello") }
                    app.post("/hello", NamedHandler())
                    app.head("/probe") { ctx -> ctx.status(200) }
                    app.get("/implicit") { ctx -> ctx.result("implicit") }
                    app.get("/orders/{id}") { ctx -> ctx.result("order") }
                    app.get("/files/<path>") { ctx -> ctx.result("file") }
                    app.get("/trailing/") { ctx -> ctx.result("trailing") }
                },
            ) { port ->
                assertEquals(
                    setOf(
                        "GET /hello",
                        "POST /hello",
                        "HEAD /probe",
                        "GET /implicit",
                        "GET /orders/{id}",
                        "GET /api/v1/items/{id}",
                        "GET /files/*",
                        "GET /trailing",
                    ),
                    identities(registry),
                    "before, after, beforeMatched and afterMatched handlers are not endpoints",
                )
                assertTrue(registry.endpoints().all { it.discoverySource == EndpointDiscoverySource.REGISTRATION })
                assertTrue(registry.endpoints().all { it.framework == "javalin-6" })

                send(port, "GET", "/hello")
                send(port, "GET", "/hello")
                send(port, "POST", "/hello")
                send(port, "HEAD", "/probe")
                assertEquals(200, send(port, "HEAD", "/implicit"))
                send(port, "GET", "/orders/7")
                send(port, "GET", "/api/v1/items/3")
                send(port, "GET", "/files/a/b")
                send(port, "GET", "/trailing")
                assertEquals(404, send(port, "GET", "/nothing"))

                assertEquals(
                    mapOf(
                        "GET /hello" to 2L,
                        "POST /hello" to 1L,
                        "HEAD /probe" to 1L,
                        "GET /orders/{id}" to 1L,
                        "GET /api/v1/items/{id}" to 1L,
                        "GET /files/*" to 1L,
                        "GET /trailing" to 1L,
                    ),
                    hits(registry),
                    "an implicit HEAD and a 404 count nothing, and a request counts once whatever filters ran",
                )
            }
        }
    }

    @Test
    fun `a request a before-handler rejects, redirects or skips counts nothing`() {
        withModules { registry ->
            serve(
                routes = { app ->
                    app.before("/secure") { throw UnauthorizedResponse() }
                    app.before("/skipped") { ctx -> ctx.skipRemainingHandlers() }
                    app.before("/redirected") { ctx -> ctx.redirect("/open") }
                    app.get("/secure") { ctx -> ctx.result("secure") }
                    app.get("/redirected") { ctx -> ctx.result("redirected") }
                    app.get("/skipped") { ctx -> ctx.result("skipped") }
                    app.get("/open") { ctx -> ctx.result("open") }
                },
            ) { port ->
                assertEquals(401, send(port, "GET", "/secure"))
                send(port, "GET", "/skipped")
                assertEquals(302, send(port, "GET", "/redirected"))
                send(port, "GET", "/open")

                assertEquals(setOf("GET /secure", "GET /skipped", "GET /redirected", "GET /open"), identities(registry))
                assertEquals(mapOf("GET /open" to 1L), hits(registry))
            }
        }
    }

    @Test
    fun `a WebSocket route is not declared and an SSE route is declared without a handler`() {
        withModules { registry ->
            serve(
                routes = { app ->
                    app.ws("/chat") { ws -> ws.onMessage { } }
                    app.sse("/events") { client -> client.close() }
                    app.get("/plain") { ctx -> ctx.result("plain") }
                },
            ) {
                assertEquals(setOf("GET /events", "GET /plain"), identities(registry))
                val sse = registry.endpoints().single { it.routeTemplate == "/events" }
                assertNull(sse.handlerClass, "SseHandler is Javalin's own wrapper")
                assertNull(sse.handlerMethod)
            }
        }
    }

    @Test
    fun `handlers join to the method that serves the endpoint`() {
        withModules { registry ->
            serve(
                routes = { app ->
                    app.get("/lambda") { ctx -> ctx.result("lambda") }
                    app.get("/named", NamedHandler())
                    app.get("/by-reference", ::referencedHandler)
                },
            ) {
                val byIdentity = registry.endpoints().associateBy { "${it.verb} ${it.routeTemplate}" }

                val lambda = byIdentity.getValue("GET /lambda")
                assertEquals(javaClass.name, lambda.handlerClass)
                assertTrue("\$lambda\$" in lambda.handlerMethod!!, "${lambda.handlerMethod} should be a lambda body")
                assertEquals(HANDLE_DESCRIPTOR, lambda.handlerDescriptor)

                val named = byIdentity.getValue("GET /named")
                assertEquals(NamedHandler::class.java.name, named.handlerClass)
                assertEquals("handle", named.handlerMethod)
                assertEquals(HANDLE_DESCRIPTOR, named.handlerDescriptor)

                val referenced = byIdentity.getValue("GET /by-reference")
                assertEquals("${NamedHandler::class.java.packageName}.FixturesKt", referenced.handlerClass)
                assertEquals("referencedHandler", referenced.handlerMethod)
                assertEquals(HANDLE_DESCRIPTOR, referenced.handlerDescriptor)
            }
        }
    }

    @Test
    fun `a CrudHandler joins its five endpoints to its five methods`() {
        withModules { registry ->
            serve(
                configure = { cfg ->
                    cfg.router.apiBuilder {
                        ApiBuilder.path("/api") {
                            ApiBuilder.crud("/users/{user-id}", UserCrud())
                        }
                    }
                },
                routes = { app -> app.get("/after-crud") { ctx -> ctx.result("after") } },
            ) { port ->
                val byIdentity = registry.endpoints().associateBy { "${it.verb} ${it.routeTemplate}" }
                assertEquals(
                    setOf(
                        "GET /api/users/{user-id}",
                        "GET /api/users",
                        "POST /api/users",
                        "PATCH /api/users/{user-id}",
                        "DELETE /api/users/{user-id}",
                        "GET /after-crud",
                    ),
                    byIdentity.keys,
                )
                val crudMethods =
                    mapOf(
                        "GET /api/users/{user-id}" to ("getOne" to ITEM_DESCRIPTOR),
                        "GET /api/users" to ("getAll" to HANDLE_DESCRIPTOR),
                        "POST /api/users" to ("create" to HANDLE_DESCRIPTOR),
                        "PATCH /api/users/{user-id}" to ("update" to ITEM_DESCRIPTOR),
                        "DELETE /api/users/{user-id}" to ("delete" to ITEM_DESCRIPTOR),
                    )
                crudMethods.forEach { (identity, joined) ->
                    val endpoint = byIdentity.getValue(identity)
                    assertEquals(UserCrud::class.java.name, endpoint.handlerClass, identity)
                    assertEquals(joined.first, endpoint.handlerMethod, identity)
                    assertEquals(joined.second, endpoint.handlerDescriptor, identity)
                }
                assertTrue(
                    "\$lambda\$" in byIdentity.getValue("GET /after-crud").handlerMethod!!,
                    "a registration after crud is joined on its own",
                )

                send(port, "GET", "/api/users/5")
                send(port, "GET", "/api/users")
                send(port, "POST", "/api/users")
                send(port, "PATCH", "/api/users/5")
                send(port, "DELETE", "/api/users/5")
                assertEquals(
                    setOf(
                        "GET /api/users/{user-id}",
                        "GET /api/users",
                        "POST /api/users",
                        "PATCH /api/users/{user-id}",
                        "DELETE /api/users/{user-id}",
                    ),
                    hits(registry).keys,
                )
            }
        }
    }

    @Test
    fun `a CrudHandler that throws leaves later registrations their own join`() {
        withModules { registry ->
            serve(
                configure = { cfg ->
                    cfg.router.apiBuilder {
                        runCatching { ApiBuilder.crud("/no-parameter", UserCrud()) }
                        ApiBuilder.get("/later") { ctx -> ctx.result("later") }
                    }
                },
            ) { _ ->
                val later = registry.endpoints().single { it.routeTemplate == "/later" }
                assertTrue("\$lambda\$" in later.handlerMethod!!, "joined: ${later.handlerMethod}")
            }
        }
    }

    @Test
    fun `endpoints Javalin adds for itself are declared and counted`() {
        withModules { registry ->
            serve(
                configure = { cfg -> cfg.bundledPlugins.enableHttpAllowedMethodsOnRoutes() },
                routes = { app -> app.get("/hello") { ctx -> ctx.result("hello") } },
            ) { port ->
                assertEquals(setOf("GET /hello", "OPTIONS /hello"), identities(registry))

                send(port, "OPTIONS", "/hello")

                assertEquals(mapOf("OPTIONS /hello" to 1L), hits(registry))
            }
        }
    }

    @Test
    fun `a CORS preflight the CORS plugin answers counts nothing`() {
        withModules { registry ->
            serve(
                configure = { cfg -> cfg.bundledPlugins.enableCors { cors -> cors.addRule { it.anyHost() } } },
                routes = { app -> app.get("/hello") { ctx -> ctx.result("hello") } },
            ) { port ->
                val preflight = mapOf("Origin" to "http://example.com", "Access-Control-Request-Method" to "GET")
                assertEquals(200, send(port, "OPTIONS", "/hello", preflight))
                assertEquals(emptyMap(), hits(registry))

                send(port, "GET", "/hello", mapOf("Origin" to "http://example.com"))
                assertEquals(mapOf("GET /hello" to 1L), hits(registry))
            }
        }
    }

    @Test
    fun `only the module of the release in use acts`() {
        withModules { registry: EndpointRegistry ->
            serve(routes = { app -> app.get("/hello") { ctx -> ctx.result("hello") } }) { port ->
                send(port, "GET", "/hello")

                assertEquals(setOf("javalin-6"), registry.endpoints().map { it.framework }.toSet())
                assertEquals(mapOf("GET /hello" to 1L), hits(registry))
            }
        }
    }
}
