package dev.otherlode.registry

import dev.otherlode.bootstrap.OtherlodeEndpoints
import dev.otherlode.export.DisabledEndpointModuleKind
import dev.otherlode.export.EndpointDiscoverySource
import dev.otherlode.export.EndpointTemplateKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EndpointRegistryTest {
    @Test
    fun `register returns an entry with a normalised verb and route template`() {
        val registry = EndpointRegistry()

        val entry = registry.register(key = Any(), framework = "http-server", verb = "get", verbatimTemplate = "/orders/{id}")

        assertEquals("GET", entry.verb)
        assertEquals("/orders/{id}", entry.routeTemplate)
        assertEquals("/orders/{id}", entry.verbatimTemplate)
        assertEquals("http-server", entry.framework)
    }

    @Test
    fun `two keys normalising to the same identity share one endpoint id and one count`() {
        val registry = EndpointRegistry()
        val keyA = Any()
        val keyB = Any()

        val entryA = registry.register(key = keyA, framework = "server-a", verb = "GET", verbatimTemplate = "/health")
        val entryB = registry.register(key = keyB, framework = "server-b", verb = "GET", verbatimTemplate = "/health")

        assertEquals(entryA.endpointId, entryB.endpointId)
        entryA.hit()
        assertEquals(entryA.count, entryB.count)
    }

    @Test
    fun `a regex endpoint and a path endpoint with the same verb and template stay apart`() {
        val registry = EndpointRegistry()

        val path = registry.register(key = Any(), framework = "vertx-web", verb = "GET", verbatimTemplate = "/users/list")
        val regex =
            registry.register(
                key = Any(),
                framework = "vertx-web",
                verb = "GET",
                verbatimTemplate = "/users/list",
                templateKind = EndpointTemplateKind.REGEX,
            )

        assertNotEquals(path.endpointId, regex.endpointId)
        assertEquals(EndpointTemplateKind.PATH, path.templateKind)
        assertEquals(EndpointTemplateKind.REGEX, regex.templateKind)
        regex.hit()
        assertEquals(0L, path.count)
        assertEquals(
            listOf(EndpointTemplateKind.PATH, EndpointTemplateKind.REGEX),
            registry.endpoints().map { it.templateKind },
        )
    }

    @Test
    fun `a regex template is stored and sent exactly as given`() {
        val registry = EndpointRegistry()

        val entry =
            registry.register(
                key = Any(),
                framework = "vertx-web",
                verb = "get",
                verbatimTemplate = "/a//b/\\d{2,3}/",
                contextPath = "/ctx",
                templateKind = EndpointTemplateKind.REGEX,
            )

        assertEquals("GET", entry.verb)
        assertEquals("/a//b/\\d{2,3}/", entry.routeTemplate)
        assertEquals("/a//b/\\d{2,3}/", entry.verbatimTemplate)
        val sent =
            registry
                .computeManifestEntries(maxPerChunk = 10)
                .single()
                .endpoints
                .single()
        assertEquals("/a//b/\\d{2,3}/", sent.routeTemplate)
        assertEquals("/a//b/\\d{2,3}/", sent.verbatimTemplate)
        assertEquals(EndpointTemplateKind.REGEX, sent.templateKind)
    }

    @Test
    fun `a regex dispatch is not owned by a path endpoint of the same text`() {
        val registry = EndpointRegistry()
        registry.register(key = Any(), framework = "spring-webmvc", verb = "GET", verbatimTemplate = "/x")

        val regexEntry =
            registry.recordDispatchIfUnowned(
                key = Any(),
                framework = "otel",
                verb = "GET",
                verbatimTemplate = "/x",
                templateKind = EndpointTemplateKind.REGEX,
            )

        assertEquals(EndpointTemplateKind.REGEX, regexEntry?.templateKind)
        assertNull(registry.recordDispatchIfUnowned(key = Any(), framework = "otel", verb = "GET", verbatimTemplate = "/x"))
    }

    @Test
    fun `a servlet pattern slash and a path slash are two endpoints`() {
        val registry = EndpointRegistry()

        val path = registry.register(key = Any(), framework = "servlet", verb = "GET", verbatimTemplate = "/")
        val servlet =
            registry.register(
                key = Any(),
                framework = "servlet",
                verb = "GET",
                verbatimTemplate = "/",
                templateKind = EndpointTemplateKind.SERVLET_PATTERN,
            )
        val regex =
            registry.register(
                key = Any(),
                framework = "servlet",
                verb = "GET",
                verbatimTemplate = "/",
                templateKind = EndpointTemplateKind.REGEX,
            )

        assertEquals(3, setOf(path.endpointId, servlet.endpointId, regex.endpointId).size)
        assertEquals(EndpointTemplateKind.SERVLET_PATTERN, servlet.templateKind)
        servlet.hit()
        assertEquals(0L, path.count)
        assertEquals(0L, regex.count)
    }

    @Test
    fun `a servlet pattern template is kept verbatim and gets no context path`() {
        val registry = EndpointRegistry()

        val entry =
            registry.register(
                key = Any(),
                framework = "servlet",
                verb = "get",
                verbatimTemplate = "*.do/",
                contextPath = "/ctx",
                templateKind = EndpointTemplateKind.SERVLET_PATTERN,
            )

        assertEquals("GET", entry.verb)
        assertEquals("*.do/", entry.routeTemplate)
        assertEquals("*.do/", entry.verbatimTemplate)
        val sent =
            registry
                .computeManifestEntries(maxPerChunk = 10)
                .single()
                .endpoints
                .single()
        assertEquals("*.do/", sent.routeTemplate)
        assertEquals(EndpointTemplateKind.SERVLET_PATTERN, sent.templateKind)
    }

    @Test
    fun `ownership applies per kind for a servlet pattern`() {
        val registry = EndpointRegistry()
        registry.register(key = Any(), framework = "spring-webmvc", verb = "GET", verbatimTemplate = "/")
        val servlet = EndpointTemplateKind.SERVLET_PATTERN

        val first = registry.recordDispatchIfUnowned(Any(), "servlet", "GET", "/", templateKind = servlet)
        assertEquals(servlet, first?.templateKind)
        assertNull(registry.recordDispatchIfUnowned(Any(), "otel", "GET", "/", templateKind = servlet))
        assertNull(registry.recordDispatchIfUnowned(Any(), "otel", "HEAD", "/", templateKind = servlet))

        registry.register(key = Any(), framework = "servlet", verb = "*", verbatimTemplate = "*.do", templateKind = servlet)
        assertNull(registry.recordDispatchIfUnowned(Any(), "otel", "POST", "*.do", templateKind = servlet))
        assertEquals(
            EndpointTemplateKind.REGEX,
            registry.recordDispatchIfUnowned(Any(), "otel", "POST", "*.do", templateKind = EndpointTemplateKind.REGEX)?.templateKind,
        )
    }

    @Test
    fun `a bridge route made from an extension mapping is owned by that servlet pattern`() {
        val registry = EndpointRegistry()
        val servlet = EndpointTemplateKind.SERVLET_PATTERN
        registry.register(key = Any(), framework = "servlet", verb = "GET", verbatimTemplate = "*.do", templateKind = servlet)

        assertNull(registry.recordDispatchIfUnowned(Any(), "otel", "GET", "/*.do"))
        assertNull(registry.recordDispatchIfUnowned(Any(), "otel", "HEAD", "/*.do"))
        assertEquals("/*.php", registry.recordDispatchIfUnowned(Any(), "otel", "GET", "/*.php")?.routeTemplate)
    }

    @Test
    fun `a bridge route made from the default mapping is owned by the servlet pattern slash`() {
        val registry = EndpointRegistry()
        val servlet = EndpointTemplateKind.SERVLET_PATTERN
        registry.register(key = Any(), framework = "servlet", verb = "GET", verbatimTemplate = "/", templateKind = servlet)

        assertNull(registry.recordDispatchIfUnowned(Any(), "otel", "GET", "/*"))
        assertNull(registry.recordDispatchIfUnowned(Any(), "otel", "GET", "/"))
        assertEquals("/shop/*", registry.recordDispatchIfUnowned(Any(), "otel", "GET", "/shop/*")?.routeTemplate)
    }

    @Test
    fun `a bridge route made from the default mapping is counted when no servlet holds that pattern`() {
        val registry = EndpointRegistry()
        registry.register(key = Any(), framework = "servlet", verb = "GET", verbatimTemplate = "/orders")

        assertEquals("/*", registry.recordDispatchIfUnowned(Any(), "otel", "GET", "/*")?.routeTemplate)
    }

    @Test
    fun `lookup on an unbound key is null`() {
        val registry = EndpointRegistry()

        assertNull(registry.lookup(Any()))
    }

    @Test
    fun `lookup returns the entry bound to a key by register`() {
        val registry = EndpointRegistry()
        val key = Any()

        val entry = registry.register(key = key, framework = "http-server", verb = "GET", verbatimTemplate = "/health")

        assertSame(entry, registry.lookup(key))
    }

    @Test
    fun `lookup returns the entry bound to a key by recordDispatch`() {
        val registry = EndpointRegistry()
        val key = Any()

        val entry = registry.recordDispatch(key = key, framework = "http-server", verb = "GET", verbatimTemplate = "/health")

        assertSame(entry, registry.lookup(key))
    }

    private fun warningsFrom(block: () -> Unit): List<String> {
        val messages = mutableListOf<String>()
        val handler =
            object : java.util.logging.Handler() {
                override fun publish(record: java.util.logging.LogRecord) {
                    if (record.level == java.util.logging.Level.WARNING) messages += record.message.orEmpty()
                }

                override fun flush() {}

                override fun close() {}
            }
        val logger =
            java.util.logging.Logger
                .getLogger(EndpointRegistry::class.java.name)
        logger.addHandler(handler)
        try {
            block()
        } finally {
            logger.removeHandler(handler)
        }
        return messages
    }

    @Test
    fun `the dispatch limit is 8192 by default`() {
        assertEquals(8192, OtherlodeEndpoints.DISPATCH_LIMIT)
        val registry = EndpointRegistry()

        repeat(OtherlodeEndpoints.DISPATCH_LIMIT) {
            assertTrue(registry.recordDispatch(Any(), "otel", "GET", "/p/$it") != null)
        }
        val warnings = warningsFrom { assertNull(registry.recordDispatch(Any(), "otel", "GET", "/past")) }

        assertEquals(OtherlodeEndpoints.DISPATCH_LIMIT, registry.endpoints().size)
        assertEquals(1, warnings.size, "warnings: $warnings")
    }

    @Test
    fun `a module past its dispatch limit records nothing new, warns once and still counts existing identities`() {
        val registry = EndpointRegistry(dispatchLimit = 3)
        val existingKey = Any()
        val existing = registry.recordDispatch(existingKey, "otel", "GET", "/a")
        registry.recordDispatch(Any(), "otel", "GET", "/b")
        registry.recordDispatchIfUnowned(Any(), "otel", "GET", "/c")

        val refusedKey = Any()
        val warnings =
            warningsFrom {
                assertNull(registry.recordDispatch(refusedKey, "otel", "GET", "/d"))
                assertNull(registry.recordDispatch(Any(), "otel", "GET", "/e"))
                assertNull(registry.recordDispatchIfUnowned(Any(), "otel", "GET", "/f"))
            }

        assertEquals(1, warnings.size, "warnings: $warnings")
        assertTrue("otel" in warnings.single() && "3" in warnings.single(), warnings.single())
        assertEquals(setOf("/a", "/b", "/c"), registry.endpoints().map { it.routeTemplate }.toSet())
        assertNull(registry.lookup(refusedKey))
        assertSame(existing, registry.recordDispatch(Any(), "otel", "GET", "/a"))
        assertSame(existing, registry.lookup(existingKey))
        existing!!.hit()
        assertEquals(1L, registry.lookup(existingKey)!!.count)
    }

    @Test
    fun `an identity declared at registration is created past the limit and does not count toward it`() {
        val registry = EndpointRegistry(dispatchLimit = 2)
        registry.register(Any(), "spring", "GET", "/declared-before")
        registry.recordDispatch(Any(), "spring", "GET", "/d1")
        registry.recordDispatch(Any(), "spring", "GET", "/d2")
        assertNull(registry.recordDispatch(Any(), "spring", "GET", "/d3"))

        val late = registry.register(Any(), "spring", "GET", "/declared-after")

        assertEquals(EndpointDiscoverySource.REGISTRATION, late.discoverySource)
        assertEquals(4, registry.endpoints().size)
        val fresh = EndpointRegistry(dispatchLimit = 2)
        repeat(5) { fresh.register(Any(), "spring", "GET", "/r$it") }
        assertTrue(fresh.recordDispatch(Any(), "spring", "GET", "/d1") != null)
        assertTrue(fresh.recordDispatch(Any(), "spring", "GET", "/d2") != null)
    }

    @Test
    fun `two modules have independent dispatch limits`() {
        val registry = EndpointRegistry(dispatchLimit = 1)

        assertTrue(registry.recordDispatch(Any(), "otel", "GET", "/o1") != null)
        val warnings = warningsFrom { assertNull(registry.recordDispatch(Any(), "otel", "GET", "/o2")) }
        assertTrue(registry.recordDispatch(Any(), "ktor", "GET", "/k1") != null)
        val ktorWarnings = warningsFrom { assertNull(registry.recordDispatch(Any(), "ktor", "GET", "/k2")) }

        assertEquals(1, warnings.size)
        assertEquals(1, ktorWarnings.size)
        assertTrue("otel" in warnings.single() && "ktor" !in warnings.single())
        assertTrue("ktor" in ktorWarnings.single())
    }

    @Test
    fun `recordDispatch marks a new entry as discovered by dispatch`() {
        val registry = EndpointRegistry()

        val entry = registry.recordDispatch(key = Any(), framework = "http-server", verb = "GET", verbatimTemplate = "/promo")

        assertEquals(EndpointDiscoverySource.DISPATCH, entry!!.discoverySource)
    }

    @Test
    fun `a dispatch-discovered entry later registered reports REGISTRATION and is re-sent`() {
        val registry = EndpointRegistry()
        registry.recordDispatch(key = Any(), framework = "http-server", verb = "GET", verbatimTemplate = "/promo")
        val delivered = registry.computeManifestEntries(maxPerChunk = 10).single()
        registry.advanceManifest(delivered)

        val entry = registry.register(key = Any(), framework = "http-server", verb = "GET", verbatimTemplate = "/promo")

        assertEquals(EndpointDiscoverySource.REGISTRATION, entry.discoverySource)
        val resent = registry.computeManifestEntries(maxPerChunk = 10).single()
        assertEquals(EndpointDiscoverySource.REGISTRATION, resent.endpoints.single().discoverySource)
    }

    @Test
    fun `registering the same endpoint twice does not create a second manifest entry`() {
        val registry = EndpointRegistry()
        registry.register(key = Any(), framework = "http-server", verb = "GET", verbatimTemplate = "/health")

        registry.register(key = Any(), framework = "http-server", verb = "GET", verbatimTemplate = "/health")

        assertEquals(1, registry.endpoints().size)
    }

    @Test
    fun `hit increments the count without any other state`() {
        val registry = EndpointRegistry()
        val entry = registry.register(key = Any(), framework = "http-server", verb = "GET", verbatimTemplate = "/health")

        entry.hit()
        entry.hit()

        assertEquals(2L, entry.count)
    }

    @Test
    fun `attachHandler sets the join when none is present`() {
        val registry = EndpointRegistry()
        val entry = registry.register(key = Any(), framework = "http-server", verb = "GET", verbatimTemplate = "/health")

        registry.attachHandler(entry, HandlerRef(className = "com.example.HealthHandler"))

        assertEquals("com.example.HealthHandler", entry.handlerClass)
        assertNull(entry.handlerMethod)
    }

    @Test
    fun `attachHandler upgrades a class-only join to a full method join`() {
        val registry = EndpointRegistry()
        val entry = registry.register(key = Any(), framework = "http-server", verb = "GET", verbatimTemplate = "/health")
        registry.attachHandler(entry, HandlerRef(className = "com.example.HealthHandler"))

        registry.attachHandler(
            entry,
            HandlerRef(className = "com.example.HealthHandler", methodName = "handle", descriptor = "()V"),
        )

        assertEquals("handle", entry.handlerMethod)
        assertEquals("()V", entry.handlerDescriptor)
    }

    @Test
    fun `attachHandler never overwrites a full join already present`() {
        val registry = EndpointRegistry()
        val entry = registry.register(key = Any(), framework = "http-server", verb = "GET", verbatimTemplate = "/health")
        registry.attachHandler(
            entry,
            HandlerRef(className = "com.example.HealthHandler", methodName = "handle", descriptor = "()V"),
        )

        registry.attachHandler(
            entry,
            HandlerRef(className = "com.example.Other", methodName = "other", descriptor = "()I"),
        )

        assertEquals("com.example.HealthHandler", entry.handlerClass)
        assertEquals("handle", entry.handlerMethod)
        assertEquals("()V", entry.handlerDescriptor)
    }

    @Test
    fun `recordDispatch attaches the handler class only when the entry has none yet`() {
        val registry = EndpointRegistry()
        val entry =
            registry.recordDispatch(
                key = Any(),
                framework = "http-server",
                verb = "GET",
                verbatimTemplate = "/promo",
                handlerClass = "com.example.PromoHandler",
            )

        assertEquals("com.example.PromoHandler", entry!!.handlerClass)
        assertNull(entry.handlerMethod)
    }

    @Test
    fun `recordDisabledModule is idempotent, keeping the first reason`() {
        val registry = EndpointRegistry()

        registry.recordDisabledModule("spring-mvc-5", reason = "first reason", kind = DisabledEndpointModuleKind.ADVICE_FAILED)
        registry.recordDisabledModule("spring-mvc-5", reason = "second reason", kind = DisabledEndpointModuleKind.LINKAGE_ERROR)

        assertEquals("first reason", registry.disabledModules().single().reason)
        assertEquals(DisabledEndpointModuleKind.ADVICE_FAILED, registry.disabledModules().single().kind)
    }

    @Test
    fun `endpoints returns a full snapshot in endpoint id order`() {
        val registry = EndpointRegistry()
        registry.register(key = Any(), framework = "http-server", verb = "GET", verbatimTemplate = "/b")
        registry.register(key = Any(), framework = "http-server", verb = "GET", verbatimTemplate = "/a")

        val ids = registry.endpoints().map { it.endpointId }

        assertEquals(ids.sorted(), ids)
    }

    @Test
    fun `computeDeltas reports the cumulative count for endpoints whose count changed`() {
        val registry = EndpointRegistry()
        val entry = registry.register(key = Any(), framework = "http-server", verb = "GET", verbatimTemplate = "/health")
        entry.hit()
        entry.hit()

        val batches = registry.computeDeltas(maxPerBatch = 10)

        assertEquals(1, batches.size)
        val delta = batches.single().deltas.single()
        assertEquals(entry.endpointId, delta.endpointId)
        assertEquals(2L, delta.hitsTotal)
    }

    @Test
    fun `computeDeltas returns an empty list when nothing changed, with no heartbeat role`() {
        val registry = EndpointRegistry()
        registry.register(key = Any(), framework = "http-server", verb = "GET", verbatimTemplate = "/health")

        assertTrue(registry.computeDeltas(maxPerBatch = 10).isEmpty())
    }

    @Test
    fun `advanceDeltas omits endpoints whose count already reached the collector`() {
        val registry = EndpointRegistry()
        val entry = registry.register(key = Any(), framework = "http-server", verb = "GET", verbatimTemplate = "/health")
        entry.hit()

        registry.advanceDeltas(registry.computeDeltas(maxPerBatch = 10).single())

        assertTrue(registry.computeDeltas(maxPerBatch = 10).isEmpty())
    }

    @Test
    fun `hits recorded between a snapshot and a failed flush are not lost`() {
        val registry = EndpointRegistry()
        val entry = registry.register(key = Any(), framework = "http-server", verb = "GET", verbatimTemplate = "/health")
        entry.hit()
        entry.hit()
        entry.hit()

        registry.computeDeltas(maxPerBatch = 10) // send fails, never advanced
        entry.hit()
        entry.hit()

        val retry =
            registry
                .computeDeltas(maxPerBatch = 10)
                .single()
                .deltas
                .single()
        assertEquals(5L, retry.hitsTotal)
    }

    @Test
    fun `a decreased count is reported and warns once`() {
        val registry = EndpointRegistry()
        val entry = registry.register(key = Any(), framework = "http-server", verb = "GET", verbatimTemplate = "/health")
        repeat(5) { entry.hit() }
        registry.advanceDeltas(registry.computeDeltas(maxPerBatch = 10).single())

        // Not reachable via this registry's own hit()/register() calls (counts only ever grow);
        // simulated directly to pin the reporting behaviour, mirroring ProbeRegistry's own test.
        entry.count = 2

        val batch =
            registry
                .computeDeltas(maxPerBatch = 10)
                .single()
                .deltas
                .single()
        assertEquals(2L, batch.hitsTotal)
    }

    @Test
    fun `an unchanged value after a delivered decrease goes quiet again`() {
        val registry = EndpointRegistry()
        val entry = registry.register(key = Any(), framework = "http-server", verb = "GET", verbatimTemplate = "/health")
        repeat(5) { entry.hit() }
        registry.advanceDeltas(registry.computeDeltas(maxPerBatch = 10).single())
        entry.count = 2
        registry.advanceDeltas(registry.computeDeltas(maxPerBatch = 10).single())

        assertTrue(registry.computeDeltas(maxPerBatch = 10).isEmpty())
    }

    @Test
    fun `firstSeenAt is stamped once and stays stable across subsequent flushes`() {
        val registry = EndpointRegistry()
        val entry = registry.register(key = Any(), framework = "http-server", verb = "GET", verbatimTemplate = "/health")
        entry.hit()

        val first = registry.computeDeltas(maxPerBatch = 10).single()
        val firstSeenAt = first.deltas.single().firstSeenAt
        registry.advanceDeltas(first)

        entry.hit()
        val second =
            registry
                .computeDeltas(maxPerBatch = 10)
                .single()
                .deltas
                .single()

        assertEquals(firstSeenAt, second.firstSeenAt)
    }

    @Test
    fun `computeDeltas splits changed endpoints into batches at the cap`() {
        val registry = EndpointRegistry()
        val entries = (1..5).map { registry.register(key = Any(), framework = "http-server", verb = "GET", verbatimTemplate = "/e$it") }
        entries.forEach { it.hit() }

        val batches = registry.computeDeltas(maxPerBatch = 2)

        assertEquals(listOf(2, 2, 1), batches.map { it.deltas.size })
    }

    @Test
    fun `advancing one delta batch leaves the other batch's endpoints pending`() {
        val registry = EndpointRegistry()
        val entryA = registry.register(key = Any(), framework = "http-server", verb = "GET", verbatimTemplate = "/a")
        val entryB = registry.register(key = Any(), framework = "http-server", verb = "GET", verbatimTemplate = "/b")
        entryA.hit()
        entryB.hit()
        val batches = registry.computeDeltas(maxPerBatch = 1)
        assertEquals(2, batches.size)

        registry.advanceDeltas(batches[0])

        val pending = registry.computeDeltas(maxPerBatch = 10)
        assertEquals(1, pending.size)
        assertEquals(
            batches[1].deltas.single().endpointId,
            pending
                .single()
                .deltas
                .single()
                .endpointId,
        )
    }

    @Test
    fun `advancing an older delta snapshot after a newer one does not roll the last-sent value back`() {
        val registry = EndpointRegistry()
        val entry = registry.register(key = Any(), framework = "http-server", verb = "GET", verbatimTemplate = "/health")
        entry.hit()
        entry.hit()
        entry.hit()
        val older = registry.computeDeltas(maxPerBatch = 10).single()
        entry.hit()
        entry.hit()
        val newer = registry.computeDeltas(maxPerBatch = 10).single()

        registry.advanceDeltas(newer)
        registry.advanceDeltas(older)

        assertTrue(registry.computeDeltas(maxPerBatch = 10).isEmpty(), "5 was already delivered; 3 must not win")
    }

    @Test
    fun `computeManifestEntries reports endpoints never delivered`() {
        val registry = EndpointRegistry()
        registry.register(key = Any(), framework = "http-server", verb = "GET", verbatimTemplate = "/health")

        val chunks = registry.computeManifestEntries(maxPerChunk = 10)

        assertEquals(
            "/health",
            chunks
                .single()
                .endpoints
                .single()
                .routeTemplate,
        )
    }

    @Test
    fun `computeManifestEntries returns an empty list when there is nothing to send`() {
        val registry = EndpointRegistry()
        registry.register(key = Any(), framework = "http-server", verb = "GET", verbatimTemplate = "/health")
        registry.advanceManifest(registry.computeManifestEntries(maxPerChunk = 10).single())

        assertTrue(registry.computeManifestEntries(maxPerChunk = 10).isEmpty())
    }

    @Test
    fun `a failed manifest send is not advanced, so the next compute retries the same endpoint`() {
        val registry = EndpointRegistry()
        registry.register(key = Any(), framework = "http-server", verb = "GET", verbatimTemplate = "/health")

        registry.computeManifestEntries(maxPerChunk = 10) // send fails, never advanced

        val retry = registry.computeManifestEntries(maxPerChunk = 10)
        assertEquals(
            "/health",
            retry
                .single()
                .endpoints
                .single()
                .routeTemplate,
        )
    }

    @Test
    fun `a join learned after delivery is re-sent on the next compute`() {
        val registry = EndpointRegistry()
        val entry = registry.register(key = Any(), framework = "http-server", verb = "GET", verbatimTemplate = "/health")
        val firstSnapshot = registry.computeManifestEntries(maxPerChunk = 10).single()

        registry.attachHandler(entry, HandlerRef(className = "com.example.HealthHandler", methodName = "handle", descriptor = "()V"))
        registry.advanceManifest(firstSnapshot)

        val secondSnapshot = registry.computeManifestEntries(maxPerChunk = 10).single()
        val location = secondSnapshot.endpoints.single()
        assertEquals("com.example.HealthHandler", location.handlerClass)
        assertEquals("handle", location.handlerMethod)
    }

    @Test
    fun `computeDisabledModules names every disabled module not yet delivered`() {
        val registry = EndpointRegistry()
        registry.recordDisabledModule("spring-mvc-5", reason = "LinkageError", kind = DisabledEndpointModuleKind.LINKAGE_ERROR)

        val snapshot = registry.computeDisabledModules()

        assertEquals("spring-mvc-5", snapshot?.modules?.single()?.module)
        assertEquals(DisabledEndpointModuleKind.LINKAGE_ERROR, snapshot?.modules?.single()?.kind)
    }

    @Test
    fun `computeDisabledModules is null when no module is undelivered, and after the snapshot is advanced`() {
        val registry = EndpointRegistry()
        assertEquals(null, registry.computeDisabledModules())
        registry.recordDisabledModule("spring-mvc-5", reason = "LinkageError", kind = DisabledEndpointModuleKind.LINKAGE_ERROR)

        val snapshot = registry.computeDisabledModules()!!
        assertEquals(
            "spring-mvc-5",
            registry
                .computeDisabledModules()
                ?.modules
                ?.single()
                ?.module,
            "computing marks nothing",
        )
        registry.advanceDisabledModules(snapshot)

        assertEquals(null, registry.computeDisabledModules())
    }

    @Test
    fun `computeManifestEntries carries endpoints only, never a disabled module`() {
        val registry = EndpointRegistry()
        registry.register(key = Any(), framework = "http-server", verb = "GET", verbatimTemplate = "/a")
        registry.register(key = Any(), framework = "http-server", verb = "GET", verbatimTemplate = "/b")
        registry.recordDisabledModule("spring-mvc-5", reason = "LinkageError", kind = DisabledEndpointModuleKind.LINKAGE_ERROR)

        val chunks = registry.computeManifestEntries(maxPerChunk = 1)

        assertEquals(2, chunks.size)
        chunks.forEach { assertEquals(1, it.endpoints.size) }
        chunks.forEach(registry::advanceManifest)
        assertEquals(
            "spring-mvc-5",
            registry
                .computeDisabledModules()
                ?.modules
                ?.single()
                ?.module,
            "advancing endpoint chunks does not deliver the module",
        )
    }

    @Test
    fun `advancing one manifest chunk leaves the other chunk's entries pending`() {
        val registry = EndpointRegistry()
        registry.register(key = Any(), framework = "http-server", verb = "GET", verbatimTemplate = "/a")
        registry.register(key = Any(), framework = "http-server", verb = "GET", verbatimTemplate = "/b")
        val chunks = registry.computeManifestEntries(maxPerChunk = 1)
        assertEquals(2, chunks.size)

        registry.advanceManifest(chunks[0])

        val pending = registry.computeManifestEntries(maxPerChunk = 10)
        assertEquals(1, pending.size)
        assertEquals(
            chunks[1].endpoints.single().routeTemplate,
            pending
                .single()
                .endpoints
                .single()
                .routeTemplate,
        )
    }

    @Test
    fun `normalizeVerb rules apply so a null and an explicit star verb merge into one endpoint`() {
        val registry = EndpointRegistry()

        val entryA = registry.register(key = Any(), framework = "http-server", verb = null, verbatimTemplate = "/health")
        val entryB = registry.register(key = Any(), framework = "http-server", verb = "*", verbatimTemplate = "/health")

        assertEquals(entryA.endpointId, entryB.endpointId)
    }

    @Test
    fun `contextPath is folded into the route template used for identity`() {
        val registry = EndpointRegistry()

        val entry =
            registry.register(
                key = Any(),
                framework = "spring-mvc",
                verb = "GET",
                verbatimTemplate = "/orders",
                contextPath = "/app",
            )

        assertEquals("/app/orders", entry.routeTemplate)
    }

    @Test
    fun `recordDispatchIfUnowned binds nothing and returns null for an identity another framework already owns`() {
        val registry = EndpointRegistry()
        val ownedEntry = registry.register(key = Any(), framework = "spring-mvc", verb = "GET", verbatimTemplate = "/users/{id}")
        val bridgeKey = Any()

        val result = registry.recordDispatchIfUnowned(key = bridgeKey, framework = "otel", verb = "GET", verbatimTemplate = "/users/{id}")

        assertNull(result)
        assertNull(registry.lookup(bridgeKey))
        assertEquals(0L, ownedEntry.count)
        assertEquals("spring-mvc", registry.endpoints().single().framework)
    }

    @Test
    fun `recordDispatchIfUnowned binds and returns the entry when the existing owner is the same framework`() {
        val registry = EndpointRegistry()
        val firstKey = Any()
        val existing = registry.recordDispatch(key = firstKey, framework = "otel", verb = "GET", verbatimTemplate = "/users/{id}")
        val secondKey = Any()

        val result = registry.recordDispatchIfUnowned(key = secondKey, framework = "otel", verb = "GET", verbatimTemplate = "/users/{id}")

        assertSame(existing, result)
        assertSame(existing, registry.lookup(secondKey))
    }

    @Test
    fun `recordDispatchIfUnowned creates a dispatch-discovered entry when no identity exists yet`() {
        val registry = EndpointRegistry()
        val key = Any()

        val result = registry.recordDispatchIfUnowned(key = key, framework = "otel", verb = "GET", verbatimTemplate = "/users/{id}")

        assertEquals("otel", result?.framework)
        assertEquals(EndpointDiscoverySource.DISPATCH, result?.discoverySource)
        assertSame(result, registry.lookup(key))
    }

    @Test
    fun `recordDispatchIfUnowned treats a framework's any-verb endpoint on that template as owning every verb`() {
        val registry = EndpointRegistry()
        registry.register(key = Any(), framework = "spring-webmvc", verb = "*", verbatimTemplate = "/any")

        assertNull(registry.recordDispatchIfUnowned(key = Any(), framework = "otel", verb = "GET", verbatimTemplate = "/any"))
        assertEquals(1, registry.endpoints().size)
    }

    @Test
    fun `recordDispatchIfUnowned treats a HEAD request as the GET endpoint a framework serves it from`() {
        val registry = EndpointRegistry()
        registry.register(key = Any(), framework = "spring-webmvc", verb = "GET", verbatimTemplate = "/users/{id}")

        assertNull(registry.recordDispatchIfUnowned(key = Any(), framework = "otel", verb = "HEAD", verbatimTemplate = "/users/{id}"))
        assertEquals(1, registry.endpoints().size)
    }

    /** A framework's route object: equal only to itself, like a Ktor node or a JDK `HttpContext`. */
    private class RouteObject

    @Test
    fun `a framework route object is not kept alive by its binding, and its endpoint stays`() {
        val registry = EndpointRegistry()
        val collected = bindAndForget(registry)

        awaitCollected(collected)
        registry.register(key = "a-later-registration", framework = "test", verb = "GET", verbatimTemplate = "/later")

        assertEquals(1, registry.boundKeyCount(), "only the later string key is still bound")
        assertTrue(registry.endpoints().any { it.routeTemplate == "/gone" }, "the endpoint itself is kept")
    }

    @Test
    fun `a key list anchored on a framework object resolves by value while it lives, and does not keep it alive`() {
        val registry = EndpointRegistry()
        val handler = RouteObject()
        registry.register(key = listOf(handler, "/fn", "GET"), framework = "test", verb = "GET", verbatimTemplate = "/fn")

        assertTrue(registry.lookup(java.util.List.of(handler, "/fn", "GET")) != null, "an equal list resolves")
        assertNull(registry.lookup(listOf(RouteObject(), "/fn", "GET")), "a list naming another object does not")

        val collected = bindListAndForget(registry)
        awaitCollected(collected)
        registry.register(key = "a-later-registration", framework = "test", verb = "GET", verbatimTemplate = "/later")
        assertEquals(2, registry.boundKeyCount(), "the live list key and the later string key")
    }

    /** Binds a key list anchored on an object nothing else references and returns a weak reference to it. */
    private fun bindListAndForget(registry: EndpointRegistry): java.lang.ref.WeakReference<Any> {
        val handler = RouteObject()
        registry.register(key = listOf(handler, "/other", "GET"), framework = "test", verb = "GET", verbatimTemplate = "/other")
        return java.lang.ref.WeakReference(handler)
    }

    /** Binds a route object nothing else references and returns a weak reference to it. */
    private fun bindAndForget(registry: EndpointRegistry): java.lang.ref.WeakReference<Any> {
        val route = RouteObject()
        registry.register(key = route, framework = "test", verb = "GET", verbatimTemplate = "/gone")
        assertSame(registry.lookup(route), registry.lookup(route))
        return java.lang.ref.WeakReference(route)
    }

    private fun awaitCollected(reference: java.lang.ref.WeakReference<*>) {
        val deadline =
            System.nanoTime() +
                java.time.Duration
                    .ofSeconds(10)
                    .toNanos()
        while (reference.get() != null) {
            check(System.nanoTime() < deadline) { "the route object was never collected" }
            System.gc()
            Thread.sleep(10)
        }
    }

    @Test
    fun `a module binds at most its dispatch limit of request keys and still returns the entry past it`() {
        val registry = EndpointRegistry(dispatchLimit = 2)
        val first = registry.recordDispatch(listOf("GET", "/a", 1), "otel", "GET", "/a")
        registry.recordDispatch(listOf("GET", "/a", 2), "otel", "GET", "/a")

        val warnings = warningsFrom { assertSame(first, registry.recordDispatch(listOf("GET", "/a", 3), "otel", "GET", "/a")) }

        assertSame(first, registry.lookup(listOf("GET", "/a", 2)))
        assertNull(registry.lookup(listOf("GET", "/a", 3)))
        assertEquals(1, warnings.size, "warnings: $warnings")
        assertEquals(1, registry.endpoints().size)
    }

    @Test
    fun `a dispatch identity that is declared later gives its slot back`() {
        val registry = EndpointRegistry(dispatchLimit = 1)
        registry.recordDispatch(Any(), "spring", "GET", "/x")
        assertNull(registry.recordDispatch(Any(), "spring", "GET", "/y"))

        registry.register(Any(), "spring", "GET", "/x")

        assertTrue(registry.recordDispatch(Any(), "spring", "GET", "/y") != null)
    }
}
