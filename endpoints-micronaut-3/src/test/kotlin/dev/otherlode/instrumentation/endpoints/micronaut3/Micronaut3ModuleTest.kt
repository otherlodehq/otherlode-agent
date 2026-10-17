package dev.otherlode.instrumentation.endpoints.micronaut3

import dev.otherlode.export.EndpointDiscoverySource
import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.util.concurrent.CompletionStage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val PACKAGE = "dev.otherlode.instrumentation.endpoints.micronaut3"

/**
 * Proves [Micronaut3Module] end to end against a real embedded Netty server. The same sources run
 * against every tested Micronaut release, each compiled with that release's annotation processor.
 */
class Micronaut3ModuleTest {
    private val declared =
        setOf(
            "GET /java/hello",
            "POST /java/echo",
            "GET /java/items/{id}",
            "GET /java/colon/{id?}",
            "GET /java/segment/{id?}",
            "GET /java/files/*",
            "HEAD /java/probe",
            "GET /java/nohead",
            "GET /java/primitives/{id}",
            "GET /java/generic/{id}",
            "GET /java/inherited",
            "GET /kt/hello",
            "GET /kt/susp/{id}",
            "GET /kt/susp-unit",
            "GET /boom/fail",
            "GET /programmatic",
            "HEAD /programmatic-head",
            "GET /api/declared",
            "GET /traced/own",
            "GET /traced/inherited",
            "POST /generic/save",
            "GET /overload/list/{name}",
        )

    @Test
    fun `routes are declared at registration and counted once at dispatch`() {
        withModule { registry ->
            serve { port ->
                assertEquals(declared, identities(registry))
                assertTrue(registry.endpoints().all { it.discoverySource == EndpointDiscoverySource.REGISTRATION })
                assertTrue(registry.endpoints().all { it.framework == "micronaut-3" })

                assertEquals(200, send(port, "GET", "/java/hello"))
                assertEquals(200, send(port, "GET", "/java/hello"))
                assertEquals(200, send(port, "POST", "/java/echo", mapOf("Content-Type" to "text/plain"), "x"))
                assertEquals(200, send(port, "GET", "/java/items/7"))
                assertEquals(200, send(port, "GET", "/java/colon/7"))
                assertEquals(200, send(port, "GET", "/java/segment"))
                assertEquals(200, send(port, "GET", "/java/segment/9"))
                assertEquals(200, send(port, "GET", "/java/files/a/b"))
                assertEquals(200, send(port, "GET", "/java/inherited"))
                assertEquals(200, send(port, "GET", "/programmatic"))
                assertEquals(200, send(port, "GET", "/kt/hello"))

                assertEquals(
                    mapOf(
                        "GET /java/hello" to 2L,
                        "POST /java/echo" to 1L,
                        "GET /java/items/{id}" to 1L,
                        "GET /java/colon/{id?}" to 1L,
                        "GET /java/segment/{id?}" to 2L,
                        "GET /java/files/*" to 1L,
                        "GET /java/inherited" to 1L,
                        "GET /programmatic" to 1L,
                        "GET /kt/hello" to 1L,
                    ),
                    hits(registry),
                )
                assertEquals(declared, identities(registry), "dispatch discovered no endpoint registration had missed")
            }
        }
    }

    @Test
    fun `an implicit HEAD route is not declared and a HEAD request counts against the GET endpoint`() {
        withModule { registry ->
            serve { port ->
                assertTrue("HEAD /java/hello" !in identities(registry))
                assertTrue("HEAD /java/nohead" !in identities(registry))

                assertEquals(200, send(port, "HEAD", "/java/hello"))
                assertEquals(200, send(port, "HEAD", "/java/hello"))
                assertEquals(200, send(port, "GET", "/java/hello"))
                assertEquals(405, send(port, "HEAD", "/java/nohead"))

                assertEquals(mapOf("GET /java/hello" to 3L), hits(registry))
                assertEquals(declared, identities(registry))
            }
        }
    }

    @Test
    fun `an explicit Head route is an endpoint of its own`() {
        withModule { registry ->
            serve { port ->
                assertTrue("HEAD /java/probe" in identities(registry))
                assertEquals(200, send(port, "HEAD", "/java/probe"))
                assertEquals(mapOf("HEAD /java/probe" to 1L), hits(registry))
            }
        }
    }

    @Test
    fun `a programmatic HEAD route to a method that carries Get is an endpoint of its own`() {
        withModule { registry ->
            serve { port ->
                send(port, "HEAD", "/programmatic-head")

                assertEquals(mapOf("HEAD /programmatic-head" to 1L), hits(registry))
                assertEquals(declared, identities(registry))
            }
        }
    }

    @Test
    fun `a context path stays in the template of an annotated route and is not added to a programmatic one`() {
        withModule { registry ->
            serve(mapOf("micronaut.server.context-path" to "/ctx")) { port ->
                val expected =
                    declared.map {
                        it
                            .replace(
                                " /java",
                                " /ctx/java",
                            ).replace(" /kt", " /ctx/kt")
                            .replace(" /boom", " /ctx/boom")
                            .replace(" /api", " /ctx/api")
                            .replace(" /traced", " /ctx/traced")
                            .replace(" /generic", " /ctx/generic")
                            .replace(" /overload", " /ctx/overload")
                    }
                assertEquals(expected.toSet(), identities(registry))
                assertTrue("GET /programmatic" in identities(registry))

                assertEquals(200, send(port, "GET", "/ctx/java/hello"))
                assertEquals(404, send(port, "GET", "/java/hello"))
                assertEquals(mapOf("GET /ctx/java/hello" to 1L), hits(registry))
            }
        }
    }

    @Test
    fun `a WebSocket route is not declared and an upgrade counts nothing`() {
        withModule { registry ->
            serve { port ->
                assertTrue(identities(registry).none { it.contains("/ws/") }, identities(registry).toString())

                listOf("/ws/open", "/ws/message").forEach { path ->
                    val closed = CountDownLatch(1)
                    val opened = CountDownLatch(1)
                    val socket =
                        HttpClient
                            .newHttpClient()
                            .newWebSocketBuilder()
                            .buildAsync(
                                URI.create("ws://localhost:$port$path"),
                                object : WebSocket.Listener {
                                    override fun onOpen(webSocket: WebSocket) {
                                        opened.countDown()
                                        webSocket.request(1)
                                    }

                                    override fun onClose(
                                        webSocket: WebSocket,
                                        statusCode: Int,
                                        reason: String,
                                    ): CompletionStage<*>? {
                                        closed.countDown()
                                        return null
                                    }
                                },
                            ).get(10, TimeUnit.SECONDS)
                    assertTrue(opened.await(10, TimeUnit.SECONDS))
                    socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(10, TimeUnit.SECONDS)
                    closed.await(10, TimeUnit.SECONDS)
                }
                assertEquals(400, send(port, "GET", "/ws/open"))
                assertEquals(400, send(port, "GET", "/ws/message"))

                assertEquals(emptyMap(), hits(registry))
                assertTrue(identities(registry).none { it.contains("/ws/") }, "a plain GET of a WebSocket route discovers nothing")
            }
        }
    }

    @Test
    fun `a management endpoint is declared and counted like any other route`() {
        withModule { registry ->
            serve(mapOf("endpoints.all.enabled" to true, "endpoints.all.sensitive" to false)) { port ->
                assertTrue("GET /health" in identities(registry), identities(registry).toString())

                assertEquals(200, send(port, "GET", "/health"))
                assertEquals(mapOf("GET /health" to 1L), hits(registry))
            }
        }
    }

    @Test
    fun `an Error route is not an endpoint and the route it recovers is counted once`() {
        withModule { registry ->
            serve { port ->
                assertEquals(200, send(port, "GET", "/boom/fail"))
                assertEquals(mapOf("GET /boom/fail" to 1L), hits(registry))
                assertEquals(declared, identities(registry))
            }
        }
    }

    @Test
    fun `a 404, a 405 and a CORS preflight count nothing`() {
        withModule { registry ->
            val cors =
                mapOf(
                    "micronaut.server.cors.enabled" to true,
                    "micronaut.server.cors.configurations.web.allowed-origins" to listOf("http://example.com"),
                )
            serve(cors) { port ->
                val preflight =
                    mapOf(
                        "Origin" to "http://example.com",
                        "Access-Control-Request-Method" to "GET",
                    )
                assertEquals(200, send(port, "OPTIONS", "/java/hello", preflight))
                assertEquals(404, send(port, "GET", "/nothing"))
                assertEquals(405, send(port, "DELETE", "/java/hello"))

                assertEquals(emptyMap(), hits(registry))
                assertEquals(declared, identities(registry))

                assertEquals(200, send(port, "GET", "/java/hello", mapOf("Origin" to "http://example.com")))
                assertEquals(mapOf("GET /java/hello" to 1L), hits(registry))
            }
        }
    }

    @Test
    fun `handlers join to the descriptor the method tier reads from the class file`() {
        withModule { registry ->
            serve {
                val byIdentity = registry.endpoints().associateBy { "${it.verb} ${it.routeTemplate}" }
                val joins =
                    mapOf(
                        "GET /java/hello" to ("hello" to JavaController::class.java),
                        "GET /java/primitives/{id}" to ("primitives" to JavaController::class.java),
                        "GET /java/generic/{id}" to ("generic" to JavaController::class.java),
                        "POST /java/echo" to ("echo" to JavaController::class.java),
                        "GET /kt/hello" to ("hello" to KotlinController::class.java),
                        "GET /kt/susp/{id}" to ("susp" to KotlinController::class.java),
                        "GET /kt/susp-unit" to ("suspUnit" to KotlinController::class.java),
                        "GET /programmatic" to ("handle" to ProgrammaticTarget::class.java),
                        "GET /java/inherited" to ("inherited" to JavaBaseController::class.java),
                        "GET /api/declared" to ("declared" to ApiImplementation::class.java),
                        "GET /traced/own" to ("own" to TracedController::class.java),
                        "GET /traced/inherited" to ("inherited" to JavaBaseController::class.java),
                        "POST /generic/save" to ("save" to GenericBase::class.java),
                        "HEAD /programmatic-head" to ("both" to HeadTarget::class.java),
                        "GET /overload/list/{name}" to ("list" to OverloadBase::class.java),
                    )
                joins.forEach { (identity, join) ->
                    val (methodName, declaringClass) = join
                    val endpoint = byIdentity.getValue(identity)
                    assertEquals(declaringClass.name, endpoint.handlerClass, identity)
                    assertEquals(methodName, endpoint.handlerMethod, identity)
                    val fromClassFile = classFileDescriptors(declaringClass).filter { it.first == methodName }
                    assertEquals(1, fromClassFile.size, "$identity: $fromClassFile")
                    assertEquals(fromClassFile.single().second, endpoint.handlerDescriptor, identity)
                }
                assertEquals(
                    "(Ljava/lang/String;ILkotlin/coroutines/Continuation;)Ljava/lang/Object;",
                    byIdentity.getValue("GET /kt/susp/{id}").handlerDescriptor,
                )
                assertEquals(
                    "(Lkotlin/coroutines/Continuation;)Ljava/lang/Object;",
                    byIdentity.getValue("GET /kt/susp-unit").handlerDescriptor,
                )
                assertEquals("(JZ[Ljava/lang/String;)[I", byIdentity.getValue("GET /java/primitives/{id}").handlerDescriptor)
                assertEquals(
                    "(Ljava/lang/Number;Ljava/util/List;)Ljava/util/List;",
                    byIdentity.getValue("GET /java/generic/{id}").handlerDescriptor,
                )
                assertNull(byIdentity["GET /nothing"])
            }
        }
    }

    @Test
    fun `an interface default method is no route and counts nothing`() {
        withModule { registry ->
            serve { port ->
                assertEquals(404, send(port, "GET", "/api/defaulted"))
                assertEquals(404, send(port, "GET", "/diamond/m"))
                assertEquals(200, send(port, "GET", "/api/declared"))

                assertEquals(mapOf("GET /api/declared" to 1L), hits(registry))
                assertEquals(declared, identities(registry))
            }
        }
    }

    @Test
    fun `the module is never disabled by a server that starts and stops twice`() {
        withModule { registry ->
            repeat(2) {
                serve { port ->
                    assertEquals(200, send(port, "GET", "/java/hello"))
                }
            }
            assertEquals(declared, identities(registry))
            assertEquals(mapOf("GET /java/hello" to 2L), hits(registry))
        }
    }

    private fun classFileDescriptors(type: Class<*>): List<Pair<String, String>> {
        val bytes = type.getResourceAsStream("/${type.name.replace('.', '/')}.class")!!.use { it.readAllBytes() }
        val methods = mutableListOf<Pair<String, String>>()
        ClassReader(bytes).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor? {
                    methods += name to descriptor
                    return null
                }
            },
            ClassReader.SKIP_CODE,
        )
        return methods
    }
}
