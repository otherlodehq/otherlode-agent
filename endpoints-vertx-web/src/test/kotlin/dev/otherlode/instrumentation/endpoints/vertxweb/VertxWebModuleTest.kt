package dev.otherlode.instrumentation.endpoints.vertxweb

import dev.otherlode.export.EndpointDiscoverySource
import dev.otherlode.export.EndpointTemplateKind
import io.vertx.core.Future
import io.vertx.core.Handler
import io.vertx.core.Vertx
import io.vertx.core.http.HttpMethod
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.ext.web.handler.StaticHandler
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val HANDLE_DESCRIPTOR = "(Lio/vertx/ext/web/RoutingContext;)V"

private fun end(text: String = "ok") = Handler<RoutingContext> { ctx -> ctx.response().end(text) }

/** Proves [VertxWebModule] end to end against a real Vert.x Web server. The same sources run against every tested release. */
class VertxWebModuleTest {
    @Test
    fun `routes are declared at the first request and counted from it`() {
        withModule { registry ->
            serve({ _, router ->
                router.get("/hello").handler(end())
                router.post("/hello").handler(end())
                router.get("/orders/:id").handler(end())
                router.get("/files/*").handler(end())
            }) {
                assertEquals(emptySet(), identities(registry), "nothing is declared before a request reaches the router")

                send(port, "GET", "/hello")
                assertEquals(
                    setOf("GET /hello", "POST /hello", "GET /orders/{id}", "GET /files/*"),
                    identities(registry),
                )
                assertTrue(registry.endpoints().all { it.discoverySource == EndpointDiscoverySource.REGISTRATION })
                assertTrue(registry.endpoints().all { it.framework == "vertx-web" })
                assertTrue(registry.endpoints().all { it.templateKind == EndpointTemplateKind.PATH })

                send(port, "GET", "/hello")
                send(port, "POST", "/hello")
                send(port, "GET", "/orders/7")
                send(port, "GET", "/files/a/b")
                assertEquals(404, send(port, "GET", "/nothing"))
                assertEquals(405, send(port, "DELETE", "/hello"))

                assertEquals(
                    mapOf("GET /hello" to 2L, "POST /hello" to 1L, "GET /orders/{id}" to 1L, "GET /files/*" to 1L),
                    hits(registry),
                    "a 404 and a 405 count nothing",
                )
            }
        }
    }

    @Test
    fun `an instance that never gets a request declares nothing`() {
        withModule { registry ->
            serve({ vertx, router ->
                router.get("/hello").handler(end())
                router.route("/api/*").subRouter(Router.router(vertx).also { it.get("/x").handler(end()) })
            }) {
                assertEquals(emptySet(), identities(registry))
                assertEquals(emptyMap(), hits(registry))
            }
        }
    }

    @Test
    fun `a route changed after its handler was set is declared with its final path and verbs`() {
        withModule { registry ->
            serve({ _, router ->
                val route = router.route().handler(end())
                route.path("/final")
                route.method(HttpMethod.GET)
                route.method(HttpMethod.POST)
            }) {
                send(port, "GET", "/final")
                send(port, "POST", "/final")
                send(port, "POST", "/final")

                assertEquals(setOf("GET /final", "POST /final"), identities(registry))
                assertEquals(mapOf("GET /final" to 1L, "POST /final" to 2L), hits(registry))
            }
        }
    }

    @Test
    fun `a route with no path is declared as a wildcard and each of several verbs is an endpoint`() {
        withModule { registry ->
            serve({ _, router ->
                router.route().handler(Handler { ctx -> ctx.next() })
                router
                    .route("/multi")
                    .method(HttpMethod.GET)
                    .method(HttpMethod.PUT)
                    .method(HttpMethod.POST)
                    .handler(end())
                router.route("/any").handler(end())
            }) {
                send(port, "GET", "/multi")
                send(port, "PUT", "/multi")
                send(port, "PUT", "/multi")
                assertEquals(405, send(port, "DELETE", "/multi"))
                send(port, "DELETE", "/any")

                assertEquals(setOf("* /*", "GET /multi", "PUT /multi", "POST /multi", "* /any"), identities(registry))
                assertEquals(
                    mapOf("* /*" to 5L, "GET /multi" to 1L, "PUT /multi" to 2L, "* /any" to 1L),
                    hits(registry),
                    "the pass-through route is hit by every request that reaches it, a 405 included",
                )
            }
        }
    }

    @Test
    fun `a static and a parameterised sub-router mount compose the template`() {
        withModule { registry ->
            serve({ vertx, router ->
                val api = Router.router(vertx)
                api.route().handler(Handler { ctx -> ctx.next() })
                api.get("/users/:id").handler(end())
                router.route("/api/*").subRouter(api)

                val members = Router.router(vertx)
                members.get("/members/:member").handler(end())
                router.route("/orgs/:org/*").subRouter(members)

                val inner = Router.router(vertx)
                inner.get("/c").handler(end())
                val middle = Router.router(vertx)
                middle.route("/b/*").subRouter(inner)
                router.route("/a/*").subRouter(middle)

                val atRoot = Router.router(vertx)
                atRoot.get("/rooted").handler(end())
                router.route("/*").subRouter(atRoot)

                val pathless = Router.router(vertx)
                pathless.get("/bare").handler(end())
                router.route().subRouter(pathless)
            }) {
                send(port, "GET", "/api/users/7")
                send(port, "GET", "/orgs/acme/members/9")
                send(port, "GET", "/orgs/other/members/1")
                send(port, "GET", "/a/b/c")
                send(port, "GET", "/rooted")
                send(port, "GET", "/bare")

                assertEquals(
                    setOf(
                        "GET /api/users/{id}",
                        "* /api/*",
                        "GET /orgs/{org}/members/{member}",
                        "GET /a/b/c",
                        "GET /rooted",
                        "GET /bare",
                    ),
                    identities(registry),
                    "a mount is no endpoint",
                )
                assertEquals(
                    mapOf(
                        "GET /api/users/{id}" to 1L,
                        "* /api/*" to 1L,
                        "GET /orgs/{org}/members/{member}" to 2L,
                        "GET /a/b/c" to 1L,
                        "GET /rooted" to 1L,
                        "GET /bare" to 1L,
                    ),
                    hits(registry),
                )
            }
        }
    }

    @Test
    fun `a router mounted in two static places declares an endpoint for each and counts the one the request used`() {
        withModule { registry ->
            serve({ vertx, router ->
                val shared = Router.router(vertx)
                shared.get("/ping").handler(end())
                shared
                    .route("/both")
                    .method(HttpMethod.GET)
                    .method(HttpMethod.POST)
                    .handler(end())
                router.route("/a/*").subRouter(shared)
                router.route("/b/*").subRouter(shared)
            }) {
                send(port, "GET", "/a/ping")
                send(port, "GET", "/b/ping")
                send(port, "GET", "/b/ping")
                send(port, "POST", "/a/both")
                send(port, "GET", "/b/both")

                assertEquals(
                    setOf("GET /a/ping", "GET /b/ping", "GET /a/both", "POST /a/both", "GET /b/both", "POST /b/both"),
                    identities(registry),
                )
                assertEquals(
                    mapOf("GET /a/ping" to 1L, "GET /b/ping" to 2L, "POST /a/both" to 1L, "GET /b/both" to 1L),
                    hits(registry),
                )
            }
        }
    }

    @Test
    fun `a router reachable through a parameterised mount and a static mount counts the one the request used`() {
        withModule { registry ->
            serve({ vertx, router ->
                val shared = Router.router(vertx)
                shared.get("/ping").handler(end())
                shared
                    .route("/both")
                    .method(HttpMethod.GET)
                    .method(HttpMethod.POST)
                    .handler(end())
                router.route("/a/*").subRouter(shared)
                router.route("/u/:id/*").subRouter(shared)
            }) {
                send(port, "GET", "/u/1/ping")
                send(port, "GET", "/a/ping")
                send(port, "GET", "/u/2/ping")
                send(port, "POST", "/u/3/both")

                assertEquals(
                    mapOf("GET /u/{id}/ping" to 2L, "GET /a/ping" to 1L, "POST /u/{id}/both" to 1L),
                    hits(registry),
                )
            }
        }
    }

    @Test
    fun `a router used as one server's root and under another's parameterised mount counts the one the request used`() {
        withModule { registry ->
            val vertx = Vertx.vertx()
            try {
                val shared = Router.router(vertx)
                shared.get("/ping").handler(end())
                val mounting = Router.router(vertx).also { it.route("/u/:id/*").subRouter(shared) }
                val servers =
                    listOf(shared, mounting).map {
                        vertx
                            .createHttpServer()
                            .requestHandler(it)
                            .listen(0)
                            .toCompletionStage()
                            .toCompletableFuture()
                            .get(10, TimeUnit.SECONDS)
                    }

                send(servers[0].actualPort(), "GET", "/ping")
                send(servers[1].actualPort(), "GET", "/u/1/ping")
                send(servers[0].actualPort(), "GET", "/ping")
                send(servers[0].actualPort(), "GET", "/ping")

                assertEquals(mapOf("GET /ping" to 3L, "GET /u/{id}/ping" to 1L), hits(registry))
            } finally {
                vertx
                    .close()
                    .toCompletionStage()
                    .toCompletableFuture()
                    .get(10, TimeUnit.SECONDS)
            }
        }
    }

    @Test
    fun `a parameterised mount replaced by another on the same router keeps the module on`() {
        withModule { registry ->
            serve({ vertx, router ->
                val shared = Router.router(vertx)
                shared.get("/ping").handler(end())
                val first = router.route("/a/:x/*").subRouter(shared)
                router.get("/swap").handler { ctx ->
                    first.remove()
                    router.route("/b/:y/*").subRouter(shared)
                    ctx.response().end()
                }
            }) {
                send(port, "GET", "/a/1/ping")
                send(port, "GET", "/swap")
                send(port, "GET", "/b/1/ping")

                assertEquals(mapOf("GET /a/{x}/ping" to 1L, "GET /swap" to 1L, "GET /b/{y}/ping" to 1L), hits(registry))
            }
        }
    }

    @Test
    fun `a route walked while subRouter is mounting it is not declared as a leaf`() {
        val module = VertxWebModule()
        withModule(module) { registry ->
            val vertx = Vertx.vertx()
            try {
                val root = Router.router(vertx)
                root.get("/hello").handler(end())
                module.declare(root)
                val real = Router.router(vertx).also { it.get("/x").handler(end()) }
                val walking =
                    java.lang.reflect.Proxy.newProxyInstance(
                        Router::class.java.classLoader,
                        arrayOf(Router::class.java),
                    ) { _, method, args ->
                        if (method.name == "modifiedHandler") module.declare(root)
                        method.invoke(real, *(args ?: emptyArray()))
                    } as Router
                root.route("/api/*").subRouter(walking)
                module.declare(root)

                assertEquals(setOf("GET /hello", "GET /api/x"), identities(registry))
            } finally {
                vertx
                    .close()
                    .toCompletionStage()
                    .toCompletableFuture()
                    .get(10, TimeUnit.SECONDS)
            }
        }
    }

    @Test
    fun `a handler a library wraps gets no join`() {
        withModule { registry ->
            serve({ _, router ->
                router.get("/wrapped").handler(
                    io.vertx.lang.rx
                        .WrappingHandler(end()),
                )
            }) {
                send(port, "GET", "/wrapped")

                val endpoint = registry.endpoints().single { it.routeTemplate == "/wrapped" }
                assertEquals(null, endpoint.handlerClass)
                assertEquals(mapOf("GET /wrapped" to 1L), hits(registry))
            }
        }
    }

    @Test
    fun `a router reachable through two parameterised mounts fails the walk`() {
        val module = VertxWebModule()
        withModule(module) { _ ->
            val vertx = Vertx.vertx()
            try {
                val root = Router.router(vertx)
                val shared = Router.router(vertx)
                shared.get("/ping").handler(end())
                root.route("/a/:x/*").subRouter(shared)
                root.route("/b/:y/*").subRouter(shared)

                val failure = assertFailsWith<IllegalStateException> { module.declare(root) }
                assertTrue("two parameterised mounts" in failure.message!!, failure.message)
            } finally {
                vertx
                    .close()
                    .toCompletionStage()
                    .toCompletableFuture()
                    .get(10, TimeUnit.SECONDS)
            }
        }
    }

    @Test
    fun `a regex route is declared and counted under its regex source, whatever it is named`() {
        withModule { registry ->
            serve({ vertx, router ->
                router.routeWithRegex(HttpMethod.GET, "/items/(\\d+)").handler(end())
                router.getWithRegex("/named/.*").setName("getNamed").handler(end())
                router.get("/items/:id").handler(end())
                val sub = Router.router(vertx)
                sub.routeWithRegex("/inner/(?<id>[a-z]+)").handler(end())
                router.route("/mounted/*").subRouter(sub)
            }) {
                send(port, "GET", "/items/12")
                send(port, "GET", "/named/x")
                send(port, "GET", "/mounted/inner/abc")

                val byIdentity = registry.endpoints().associateBy { "${it.verb} ${it.routeTemplate}" }
                assertEquals(
                    setOf(
                        "GET /items/(\\d+)",
                        "GET /named/.*",
                        "GET /items/{id}",
                        "* /mounted/inner/(?<id>[a-z]+)",
                    ),
                    byIdentity.keys,
                )
                assertEquals(EndpointTemplateKind.REGEX, byIdentity.getValue("GET /items/(\\d+)").templateKind)
                assertEquals(EndpointTemplateKind.REGEX, byIdentity.getValue("GET /named/.*").templateKind)
                assertEquals(EndpointTemplateKind.PATH, byIdentity.getValue("GET /items/{id}").templateKind)
                assertEquals("/items/(\\d+)", byIdentity.getValue("GET /items/(\\d+)").verbatimTemplate)
                assertEquals(
                    mapOf("GET /items/(\\d+)" to 1L, "GET /named/.*" to 1L, "* /mounted/inner/(?<id>[a-z]+)" to 1L),
                    hits(registry),
                )
            }
        }
    }

    @Test
    fun `a route added after the first request is declared on a later one, and a removed route is never retracted`() {
        withModule { registry ->
            serve({ _, router ->
                router.get("/first").handler(end())
                router.get("/doomed").handler(end())
            }) {
                send(port, "GET", "/first")
                send(port, "GET", "/doomed")
                assertEquals(setOf("GET /first", "GET /doomed"), identities(registry))

                router.get("/second").handler(end())
                router.routes.first { it.path == "/doomed" }.remove()
                send(port, "GET", "/second")
                assertEquals(404, send(port, "GET", "/doomed"))

                assertEquals(setOf("GET /first", "GET /doomed", "GET /second"), identities(registry), "a removed route stays declared")
                assertEquals(mapOf("GET /first" to 1L, "GET /doomed" to 1L, "GET /second" to 1L), hits(registry))
            }
        }
    }

    @Test
    fun `a route added to a mounted router after the first request is declared on a later one`() {
        withModule { registry ->
            val holder = arrayOfNulls<Router>(1)
            serve({ vertx, router ->
                val sub = Router.router(vertx)
                sub.get("/early").handler(end())
                holder[0] = sub
                router.route("/m/*").subRouter(sub)
            }) {
                assertEquals(200, send(port, "GET", "/m/early"))
                assertEquals(setOf("GET /m/early"), identities(registry))

                holder[0]!!.get("/late").handler(end())
                send(port, "GET", "/m/late")
                send(port, "GET", "/m/early")

                assertEquals(setOf("GET /m/early", "GET /m/late"), identities(registry))
                assertEquals(mapOf("GET /m/early" to 2L, "GET /m/late" to 1L), hits(registry))
            }
        }
    }

    @Test
    fun `a failure handler is no endpoint`() {
        withModule { registry ->
            serve({ _, router ->
                router.route("/boom").handler(Handler { ctx -> ctx.fail(500) }).failureHandler(
                    Handler { ctx -> ctx.response().setStatusCode(500).end("failed") },
                )
                router.route("/only-failures").failureHandler(Handler { ctx -> ctx.response().end("failed") })
            }) {
                assertEquals(500, send(port, "GET", "/boom"))

                assertEquals(setOf("* /boom"), identities(registry))
                assertEquals(mapOf("* /boom" to 1L), hits(registry), "the failure pass counts nothing")
            }
        }
    }

    @Test
    fun `routes a request passes through are each counted and a reroute counts again`() {
        withModule { registry ->
            serve({ _, router ->
                router.get("/chain").handler(Handler { ctx -> ctx.next() })
                router.get("/chain").handler(end())
                router.get("/redirect").handler(Handler { ctx -> ctx.reroute("/target") })
                router.get("/target").handler(end())
                router.route("/chain2").handler(Handler { ctx -> ctx.next() }).handler(end())
            }) {
                send(port, "GET", "/chain")
                send(port, "GET", "/redirect")
                send(port, "GET", "/chain2")

                assertEquals(setOf("GET /chain", "GET /redirect", "GET /target", "* /chain2"), identities(registry))
                assertEquals(
                    mapOf("GET /chain" to 2L, "GET /redirect" to 1L, "GET /target" to 1L, "* /chain2" to 1L),
                    hits(registry),
                    "two routes on one template are one endpoint, and a route with two handlers is hit once",
                )
            }
        }
    }

    @Test
    fun `the dispatch keys do not pile up per request`() {
        withModule { registry ->
            serve({ vertx, router ->
                val sub = Router.router(vertx)
                sub.get("/x").handler(end())
                sub
                    .route("/many")
                    .method(HttpMethod.GET)
                    .method(HttpMethod.POST)
                    .handler(end())
                router.route("/m/*").subRouter(sub)
                router.get("/y").handler(end())
            }) {
                send(port, "GET", "/y")
                val bound = registry.boundKeyCount()
                repeat(20) {
                    send(port, "GET", "/m/x")
                    send(port, "POST", "/m/many")
                    send(port, "GET", "/y")
                }
                assertEquals(bound, registry.boundKeyCount())
            }
        }
    }

    @Test
    fun `handlers join to the method that serves the endpoint`() {
        withModule { registry ->
            serve({ vertx, router ->
                router.get("/lambda").handler { ctx -> ctx.response().end("lambda") }
                router.get("/named").handler(NamedHandler())
                router.get("/by-reference").handler(::referencedHandler)
                router.get("/after-body").handler(BodyHandler.create()).handler(NamedHandler())
                router.get("/body-only").handler(BodyHandler.create())
                router.get("/blocking").blockingHandler { ctx -> ctx.response().end("blocking") }
                router.get("/blocking-named").blockingHandler(NamedHandler())
                router.get("/responding").respond { _ -> Future.succeededFuture("done") }
                router.route("/static/*").handler(StaticHandler.create())
                router.get("/last-wins").handler(NamedHandler()).handler { ctx -> ctx.response().end("last") }
                val sub = Router.router(vertx)
                sub.get("/named").handler(NamedHandler())
                router.route("/m/*").subRouter(sub)
            }) {
                send(port, "GET", "/lambda")
                val byIdentity = registry.endpoints().associateBy { "${it.verb} ${it.routeTemplate}" }

                val lambda = byIdentity.getValue("GET /lambda")
                assertEquals(VertxWebModuleTest::class.java.name, lambda.handlerClass)
                assertTrue("\$lambda\$" in lambda.handlerMethod!!, "${lambda.handlerMethod} should be a lambda body")
                assertEquals(HANDLE_DESCRIPTOR, lambda.handlerDescriptor)

                for (identity in listOf("GET /named", "GET /after-body", "GET /blocking-named", "GET /m/named")) {
                    val named = byIdentity.getValue(identity)
                    assertEquals(NamedHandler::class.java.name, named.handlerClass, identity)
                    assertEquals("handle", named.handlerMethod, identity)
                    assertEquals(HANDLE_DESCRIPTOR, named.handlerDescriptor, identity)
                }

                val referenced = byIdentity.getValue("GET /by-reference")
                assertEquals("${NamedHandler::class.java.packageName}.FixturesKt", referenced.handlerClass)
                assertEquals("referencedHandler", referenced.handlerMethod)

                val blocking = byIdentity.getValue("GET /blocking")
                assertEquals(lambda.handlerClass, blocking.handlerClass, "the lambda, not the decorator Vert.x wraps it in")
                assertTrue("\$lambda\$" in blocking.handlerMethod!!, "${blocking.handlerMethod} should be a lambda body")

                assertNull(byIdentity.getValue("GET /responding").handlerClass, "respond runs Vert.x's own lambda")
                assertNull(byIdentity.getValue("GET /body-only").handlerClass, "a body handler is no join")
                assertNull(byIdentity.getValue("* /static/*").handlerClass, "Vert.x Web's own handler class is no join")
                assertEquals(lambda.handlerClass, byIdentity.getValue("GET /last-wins").handlerClass)
                assertTrue("\$lambda\$" in byIdentity.getValue("GET /last-wins").handlerMethod!!)
            }
        }
    }

    @Test
    fun `a route Vert_x answers with a catch-all pair is declared as the pair`() {
        withModule { registry ->
            serve({ _, router ->
                router.route("/base").handler(end())
                router.route("/base/*").handler(end())
            }) {
                send(port, "GET", "/base")
                send(port, "GET", "/base/deep/er")

                assertEquals(setOf("* /base", "* /base/*"), identities(registry))
                assertEquals(mapOf("* /base" to 1L, "* /base/*" to 1L), hits(registry))
            }
        }
    }

    @Test
    fun `a route whose path changes after the first request is declared under the new path`() {
        withModule { registry ->
            val holder = arrayOfNulls<io.vertx.ext.web.Route>(1)
            serve({ _, router -> holder[0] = router.get("/before").handler(end()) }) {
                send(port, "GET", "/before")
                holder[0]!!.path("/after")
                send(port, "GET", "/after")

                assertEquals(setOf("GET /before", "GET /after"), identities(registry))
                assertEquals(mapOf("GET /before" to 1L, "GET /after" to 1L), hits(registry))
            }
        }
    }

    @Test
    fun `a router shared by two roots is declared by each root under the mount it has there`() {
        withModule { registry ->
            val vertx = Vertx.vertx()
            try {
                val shared = Router.router(vertx)
                shared.get("/ping").handler(end())
                val publicRoot = Router.router(vertx).also { it.route("/public/*").subRouter(shared) }
                val adminRoot = Router.router(vertx).also { it.route("/admin/:tenant/*").subRouter(shared) }
                val servers =
                    listOf(publicRoot, adminRoot).map {
                        vertx
                            .createHttpServer()
                            .requestHandler(
                                it,
                            ).listen(0)
                            .toCompletionStage()
                            .toCompletableFuture()
                            .get(10, TimeUnit.SECONDS)
                    }

                send(servers[0].actualPort(), "GET", "/public/ping")
                assertEquals(setOf("GET /public/ping"), identities(registry))
                send(servers[1].actualPort(), "GET", "/admin/acme/ping")
                send(servers[0].actualPort(), "GET", "/public/ping")

                assertEquals(setOf("GET /public/ping", "GET /admin/{tenant}/ping"), identities(registry))
                assertEquals(mapOf("GET /public/ping" to 2L, "GET /admin/{tenant}/ping" to 1L), hits(registry))
            } finally {
                vertx
                    .close()
                    .toCompletionStage()
                    .toCompletableFuture()
                    .get(10, TimeUnit.SECONDS)
            }
        }
    }

    @Test
    fun `requests racing for the first declaration all see the routes declared`() {
        withModule { registry ->
            serve({ vertx, router ->
                repeat(20) { router.get("/r$it").handler(end()) }
                val sub = Router.router(vertx)
                repeat(20) { sub.get("/s$it").handler(end()) }
                router.route("/m/*").subRouter(sub)
            }) {
                val statuses = java.util.concurrent.ConcurrentLinkedQueue<Int>()
                val threads =
                    (0 until 8).map { n ->
                        Thread {
                            statuses += send(port, "GET", if (n % 2 == 0) "/r$n" else "/m/s$n")
                        }.also { it.start() }
                    }
                threads.forEach { it.join(10_000) }

                assertEquals(List(8) { 200 }, statuses.toList())
                assertEquals(40, identities(registry).size)
                assertEquals(8L, hits(registry).values.sum())
            }
        }
    }

    @Test
    fun `a typed parameter is declared by its name`() {
        withModule { registry ->
            val routeImpl =
                Vertx::class.java.classLoader
                    .getResourceAsStream(
                        "io/vertx/ext/web/impl/RouteImpl.class",
                    )!!
                    .use { it.readBytes() }
            org.junit.jupiter.api.Assumptions.assumeTrue(
                String(routeImpl, Charsets.ISO_8859_1).contains("TYPE_PATTERNS"),
                "typed path parameters came in Vert.x Web 5.2",
            )
            serve({ _, router -> router.get("/numbers/:id::integer").handler(end()) }) {
                assertEquals(200, send(port, "GET", "/numbers/12"))
                assertEquals(404, send(port, "GET", "/numbers/twelve"))

                assertEquals(mapOf("GET /numbers/{id}" to 1L), hits(registry))
            }
        }
    }
}
