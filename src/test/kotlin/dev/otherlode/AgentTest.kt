package dev.otherlode

import com.sun.net.httpserver.HttpServer
import dev.otherlode.bootstrap.OtherlodeVerifierMessages
import dev.otherlode.dependencies.ListedDependency
import dev.otherlode.export.ClassListing
import dev.otherlode.export.DependencyDiscoverySource
import dev.otherlode.export.DependencyIdentity
import dev.otherlode.export.DependencyIdentitySource
import dev.otherlode.export.DisabledEndpointModuleKind
import dev.otherlode.export.IncludeRules
import dev.otherlode.export.ProbeManifest
import dev.otherlode.export.ProtoPayloadCodec
import dev.otherlode.export.StaticBaseline
import dev.otherlode.instrumentation.endpoints.EndpointInstrumentation
import dev.otherlode.instrumentation.endpoints.api.AdviceBinder
import dev.otherlode.instrumentation.endpoints.api.EndpointModule
import dev.otherlode.registry.DependencyOrigin
import dev.otherlode.registry.DependencyRegistry
import net.bytebuddy.agent.ByteBuddyAgent
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.DynamicType
import net.bytebuddy.matcher.ElementMatcher
import net.bytebuddy.matcher.ElementMatchers
import org.junit.jupiter.api.io.TempDir
import java.lang.instrument.Instrumentation
import java.lang.reflect.Proxy
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentTest {
    @Test
    fun `a test run's shutdown flushes, then waits for a scan that ends`() {
        val events = mutableListOf<String>()
        val flushed = CountDownLatch(1)
        val scan =
            Thread {
                flushed.await()
                synchronized(events) { events += "scan ended" }
            }.apply { start() }

        Agent.shutdown(testRun = true, scanWorker = scan, scanWait = Duration.ofSeconds(10)) {
            synchronized(events) { events += "flushed" }
            flushed.countDown()
        }

        assertEquals(listOf("flushed", "scan ended"), synchronized(events) { events.toList() })
    }

    @Test
    fun `a test run's shutdown gives up on a scan that does not end, and a production run never waits`() {
        val stuck = CompletableFuture<Unit>()
        val blocked =
            Thread { stuck.join() }.apply {
                isDaemon = true
                start()
            }
        var flushes = 0
        try {
            val started = System.nanoTime()
            Agent.shutdown(testRun = true, scanWorker = blocked, scanWait = Duration.ofMillis(100)) { flushes++ }
            assertTrue(Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(5))

            val production = System.nanoTime()
            Agent.shutdown(testRun = false, scanWorker = blocked, scanWait = Duration.ofSeconds(30)) { flushes++ }
            assertTrue(Duration.ofNanos(System.nanoTime() - production) < Duration.ofSeconds(5))

            Agent.shutdown(testRun = true, scanWorker = null, scanWait = Duration.ofSeconds(30)) { flushes++ }
            assertEquals(3, flushes)
            assertTrue(blocked.isAlive)
        } finally {
            stuck.complete(Unit)
        }
    }

    /**
     * Compares the thread names present before and after a call, rather than asserting an
     * absolute count. The test JVM can already be carrying `otherlode-*` threads left by other test
     * classes in the same Gradle test worker, so an absolute "no such thread exists" assertion
     * would be flaky for reasons unrelated to this test.
     */
    private fun currentThreadNames(): Set<String> = Thread.getAllStackTraces().keys.mapTo(mutableSetOf()) { it.name }

    @Test
    fun `enabled=false starts no otherlode threads at all`() {
        val instrumentation = ByteBuddyAgent.install()
        val before = currentThreadNames()

        val running = Agent.start("enabled=false", instrumentation)

        assertNull(running)
        val newThreadNames = currentThreadNames() - before
        assertTrue(newThreadNames.none { it.startsWith("otherlode-") }, "unexpected new otherlode- threads: $newThreadNames")
    }

    /**
     * An [Instrumentation] that records the name of every method called on it and otherwise does
     * nothing. Installing a transformer, a bootstrap search path entry or a module read edge all go
     * through it, so an empty record after a call means the call installed nothing.
     */
    private fun recordingInstrumentation(calls: MutableList<String>): Instrumentation =
        Proxy.newProxyInstance(
            Instrumentation::class.java.classLoader,
            arrayOf(Instrumentation::class.java),
        ) { _, method, _ ->
            calls += method.name
            when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                else -> null
            }
        } as Instrumentation

    @Test
    fun `no include rules refuses to start, installs nothing and starts no otherlode threads`() {
        val noUsableRule = listOf("includePackages=com.acme.*", "includePackages=com/acme;org.example.**")
        for (args in listOf(null, "", "includePackages=", "includePackages=;", "includePackages= ; ", "excludePackages=com.acme") +
            noUsableRule) {
            val calls = mutableListOf<String>()
            val before = currentThreadNames()

            val running = Agent.start(args, recordingInstrumentation(calls))

            assertNull(running, "args '$args' must be refused")
            assertEquals(emptyList(), calls, "args '$args' must not touch Instrumentation")
            val newThreadNames = currentThreadNames() - before
            assertTrue(newThreadNames.none { it.startsWith("otherlode-") }, "args '$args' started otherlode- threads: $newThreadNames")
        }
    }

    @Test
    fun `a startup step that fails after install removes every transformer it added and starts no otherlode threads`() {
        val calls = mutableListOf<String>()
        val before = currentThreadNames()

        assertFailsWith<IllegalStateException> {
            Agent.start(
                "includePackages=com.example.nothing,staticBaselineEnabled=true,exportUrl=http://localhost:1",
                recordingInstrumentation(calls),
                addShutdownHook = { throw IllegalStateException("Shutdown in progress") },
            )
        }

        assertTrue(calls.count { it == "addTransformer" } > 0, "$calls")
        assertEquals(calls.count { it == "addTransformer" }, calls.count { it == "removeTransformer" }, "$calls")
        val started = (currentThreadNames() - before).filter { it.startsWith("otherlode-") && it != "otherlode-export" }
        assertEquals(emptyList(), started, "no listing or scan thread starts once a step has failed")
    }

    @Test
    fun `a successful start spawns no dependency listing thread, since the first flush runs the listing`() {
        val before = currentThreadNames()
        val running =
            Agent.start(
                "includePackages=dev.otherlode.neverloaded.fixture,flushIntervalSeconds=3600",
                ByteBuddyAgent.install(),
            )

        try {
            assertNotNull(running)
            Thread.sleep(200)
            val started = currentThreadNames() - before
            assertTrue(
                started.none { it.contains("listing") && it != "otherlode-class-listing" },
                "unexpected dependency listing thread: $started",
            )
        } finally {
            running?.stop()
        }
    }

    @Test
    fun `premain with no include rules returns quietly`() {
        val calls = mutableListOf<String>()

        Agent.premain(null, recordingInstrumentation(calls))

        assertEquals(emptyList(), calls)
    }

    @Test
    fun `enabled=false with no include rules still returns null and installs nothing`() {
        val calls = mutableListOf<String>()
        val before = currentThreadNames()

        val running = Agent.start("enabled=false", recordingInstrumentation(calls))

        assertNull(running)
        assertEquals(emptyList(), calls)
        assertTrue((currentThreadNames() - before).none { it.startsWith("otherlode-") })
    }

    @Test
    fun `enabled (the default) starts the export scheduler`() {
        val instrumentation = ByteBuddyAgent.install()
        val before = currentThreadNames()

        // Scoped to a package nothing in this JVM ever loads, so the transformer never matches a
        // real class while it is installed. The interval keeps the scheduler from attempting a
        // flush before stop() removes it along with the shutdown hook, so the test JVM never
        // sends anything to a collector, at exit included.
        val running =
            Agent.start(
                "includePackages=dev.otherlode.neverloaded.fixture,flushIntervalSeconds=3600",
                instrumentation,
            )

        try {
            assertNotNull(running, "enabled must start the agent")
            val newThreadNames = currentThreadNames() - before
            assertTrue(newThreadNames.any { it.startsWith("otherlode-") }, "expected a new otherlode- thread, found: $newThreadNames")
        } finally {
            running?.stop()
        }
    }

    @Test
    fun `endpointsEnabled=false leaves Running's endpoint transformer null, and stop() still works`() {
        val instrumentation = ByteBuddyAgent.install()

        val running =
            Agent.start(
                "includePackages=dev.otherlode.neverloaded.fixture,flushIntervalSeconds=3600,endpointsEnabled=false",
                instrumentation,
            )

        try {
            assertNotNull(running)
            assertNull(running.endpointTransformer)
        } finally {
            running?.stop()
        }
    }

    @Test
    fun `endpointsEnabled defaults to true, leaving Running's endpoint transformer non-null, and stop() still works`() {
        val instrumentation = ByteBuddyAgent.install()

        val running =
            Agent.start(
                "includePackages=dev.otherlode.neverloaded.fixture,flushIntervalSeconds=3600",
                instrumentation,
            )

        try {
            assertNotNull(running)
            assertNotNull(running.endpointTransformer)
        } finally {
            running?.stop()
        }
    }

    @Test
    fun `premain never propagates a failure from start, since that would abort the target JVM`() {
        // Every Instrumentation call throws, so start() fails at its first use of it, before any
        // thread or transformer exists to clean up.
        val brokenInstrumentation =
            Proxy.newProxyInstance(
                Instrumentation::class.java.classLoader,
                arrayOf(Instrumentation::class.java),
            ) { _, method, _ -> throw IllegalStateException("simulated instrumentation failure in ${method.name}") } as Instrumentation

        Agent.premain("includePackages=dev.otherlode.neverloaded.fixture", brokenInstrumentation)
    }

    @Test
    fun `staticBaselineEnabled starts the scan off premain and delivers the baseline to the collector`() {
        val received = CompletableFuture<StaticBaseline>()
        val collector = HttpServer.create(InetSocketAddress("localhost", 0), 0)
        collector.createContext("/v1/otherlode/static-baseline") { exchange ->
            received.complete(ProtoPayloadCodec.decodeStaticBaseline(exchange.requestBody.readBytes()))
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        collector.start()
        val instrumentation = ByteBuddyAgent.install()
        val before = currentThreadNames()

        // The test classpath is this JVM's java.class.path, and it carries the compiled fixtures
        // under com.example.target, so a scan of the default roots must declare one of them.
        val running =
            Agent.start(
                "includePackages=com.example.target,excludePackages=com.example.target.excluded," +
                    "flushIntervalSeconds=3600,endpointsEnabled=false," +
                    "staticBaselineEnabled=true,exportUrl=http://localhost:${collector.address.port}",
                instrumentation,
            )
        try {
            assertNotNull(running)
            assertTrue((currentThreadNames() - before).any { it == "otherlode-static-baseline-scan" } || received.isDone)
            val baseline = received.get(30, TimeUnit.SECONDS)
            assertTrue(baseline.declaredClasses.any { it.className == "com.example.target.SampleTarget" })
            assertEquals(1, baseline.chunkCount)
            assertEquals(IncludeRules(listOf("com.example.target"), listOf("com.example.target.excluded")), baseline.includeRules)
        } finally {
            running?.stop()
            collector.stop(0)
        }
    }

    @Test
    fun `the class listing reaches the collector with the rules, the flush interval and the classes under them`() {
        val received = CompletableFuture<ClassListing>()
        val collector = HttpServer.create(InetSocketAddress("localhost", 0), 0)
        collector.createContext("/v1/otherlode/class-listing") { exchange ->
            received.complete(ProtoPayloadCodec.decodeClassListing(exchange.requestBody.readBytes()))
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        collector.start()
        val instrumentation = ByteBuddyAgent.install()

        // The test classpath carries the compiled fixtures under com.example.target.
        val running =
            Agent.start(
                "includePackages=com.example.target,flushIntervalSeconds=3600,endpointsEnabled=false," +
                    "exportUrl=http://localhost:${collector.address.port}",
                instrumentation,
            )
        try {
            assertNotNull(running)
            val listing = received.get(30, TimeUnit.SECONDS)
            assertTrue("com.example.target.SampleTarget" in listing.classNames, listing.classNames.toString())
            assertTrue(listing.classNames.all { it.startsWith("com.example.target.") }, listing.classNames.toString())
            assertEquals(listing.classNames.sorted(), listing.classNames)
            assertEquals(IncludeRules(listOf("com.example.target"), emptyList()), listing.includeRules)
            assertEquals(1, listing.chunkCount)
            assertEquals(running.resource, listing.resource)
            assertEquals(3600, listing.resource.flushIntervalSeconds)
        } finally {
            running?.stop()
            collector.stop(0)
        }
    }

    @Test
    fun `classListingEnabled=false sends no class listing`() {
        val paths = ConcurrentHashMap.newKeySet<String>()
        val collector = HttpServer.create(InetSocketAddress("localhost", 0), 0)
        collector.createContext("/") { exchange ->
            paths += exchange.requestURI.path
            exchange.requestBody.readBytes()
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        collector.start()
        val instrumentation = ByteBuddyAgent.install()

        val running =
            Agent.start(
                "includePackages=com.example.target,flushIntervalSeconds=3600,endpointsEnabled=false," +
                    "classListingEnabled=false,exportUrl=http://localhost:${collector.address.port}",
                instrumentation,
            )
        try {
            assertNotNull(running)
            assertNull(running.classListingWorker, "a class listing thread was made with the listing off")
            running.scheduler.flush()

            assertFalse("/v1/otherlode/class-listing" in paths, paths.toString())
        } finally {
            running?.stop()
            collector.stop(0)
        }
    }

    @Test
    fun `every payload kind from one agent start carries the same non-empty run id`() {
        val runIds = ConcurrentHashMap<String, MutableSet<String>>()
        val baselineReceived = CompletableFuture<Unit>()
        val collector = HttpServer.create(InetSocketAddress("localhost", 0), 0)
        for (path in listOf("deltas", "manifest", "static-baseline")) {
            collector.createContext("/v1/otherlode/$path") { exchange ->
                val bytes = exchange.requestBody.readBytes()
                val runId =
                    when (path) {
                        "deltas" -> ProtoPayloadCodec.decodeDeltaBatch(bytes).resource.runId
                        "manifest" -> ProtoPayloadCodec.decodeProbeManifest(bytes).resource.runId
                        else -> ProtoPayloadCodec.decodeStaticBaseline(bytes).resource.runId
                    }
                runIds.computeIfAbsent(path) { ConcurrentHashMap.newKeySet() } += runId
                if (path == "static-baseline") baselineReceived.complete(Unit)
                exchange.sendResponseHeaders(200, -1)
                exchange.close()
            }
        }
        collector.start()
        val instrumentation = ByteBuddyAgent.install()

        val running =
            Agent.start(
                "includePackages=com.example.target,flushIntervalSeconds=3600,endpointsEnabled=false," +
                    "staticBaselineEnabled=true,exportUrl=http://localhost:${collector.address.port}",
                instrumentation,
            )
        try {
            assertNotNull(running)
            baselineReceived.get(30, TimeUnit.SECONDS)
            running.scheduler.flush()

            val expected = running.resource.runId
            assertTrue(expected.isNotEmpty())
            assertEquals(setOf("deltas", "manifest", "static-baseline"), runIds.keys)
            assertTrue(runIds.values.all { it == setOf(expected) }, "every payload must carry run id $expected: $runIds")
        } finally {
            running?.stop()
            collector.stop(0)
        }
    }

    @Test
    fun `a started agent watches the verifier on every manifest, and stop restores VerifyError`() {
        val manifests = CopyOnWriteArrayList<ProbeManifest>()
        val collector = HttpServer.create(InetSocketAddress("localhost", 0), 0)
        collector.createContext("/") { exchange ->
            val bytes = exchange.requestBody.readBytes()
            if (exchange.requestURI.path == "/v1/otherlode/manifest") manifests += ProtoPayloadCodec.decodeProbeManifest(bytes)
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        collector.start()
        val running =
            Agent.start(
                "includePackages=dev.otherlode.neverloaded.fixture,flushIntervalSeconds=3600,endpointsEnabled=false," +
                    "exportUrl=http://localhost:${collector.address.port}",
                ByteBuddyAgent.install(),
            )
        try {
            assertNotNull(running)
            assertTrue(running.verifierHook.watch().watched)
            running.scheduler.flush()
            assertTrue(manifests.isNotEmpty())
            assertTrue(manifests.all { it.verifierRejectionsWatched }, "$manifests")
        } finally {
            running?.stop()
            collector.stop(0)
        }

        OtherlodeVerifierMessages.drain()
        VerifyError("after stop")
        assertTrue(OtherlodeVerifierMessages.drain().isEmpty(), "VerifyError keeps its weave after stop")
    }

    @Test
    fun `two agent starts get different run ids`() {
        val instrumentation = ByteBuddyAgent.install()
        val args = "includePackages=dev.otherlode.neverloaded.fixture,flushIntervalSeconds=3600,serviceInstanceId=pinned"

        val first = assertNotNull(Agent.start(args, instrumentation))
        first.stop()
        val second = assertNotNull(Agent.start(args, instrumentation))
        second.stop()

        assertEquals("pinned", first.resource.serviceInstanceId)
        assertEquals(first.resource.serviceInstanceId, second.resource.serviceInstanceId)
        assertNotEquals(first.resource.runId, second.resource.runId, "a pinned instance id must still get a new run id per start")
    }

    @Test
    fun `start leaves the dependency listing to the first flush, which runs it and marks it complete`() {
        val collector = HttpServer.create(InetSocketAddress("localhost", 0), 0)
        collector.createContext("/") { exchange ->
            exchange.requestBody.readBytes()
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        collector.start()
        val instrumentation = ByteBuddyAgent.install()

        val running =
            Agent.start(
                "includePackages=dev.otherlode.neverloaded.fixture,flushIntervalSeconds=3600,endpointsEnabled=false," +
                    "exportUrl=http://localhost:${collector.address.port}",
                instrumentation,
            )
        try {
            assertNotNull(running)
            assertFalse(running.dependencyRegistry.isListingComplete, "start must not list dependencies")

            running.scheduler.flush()

            assertTrue(running.dependencyRegistry.isListingComplete, "the first flush must have run the listing")
            // The test classpath carries the Kotlin stdlib as a jar, and nothing in it is under the include rules.
            assertTrue(running.dependencyRegistry.entries().any { entry -> entry.identities.any { it.artifactId == "kotlin-stdlib" } })
            assertEquals(0, running.dependencyRegistry.classIndexSize, "no class index without the static baseline")
        } finally {
            running?.stop()
            collector.stop(0)
        }
    }

    @Test
    fun `with the static baseline enabled the baseline keeps references into listed dependencies and drops JDK names`() {
        val received = CompletableFuture<StaticBaseline>()
        val collector = HttpServer.create(InetSocketAddress("localhost", 0), 0)
        collector.createContext("/v1/otherlode/static-baseline") { exchange ->
            received.complete(ProtoPayloadCodec.decodeStaticBaseline(exchange.requestBody.readBytes()))
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        collector.start()
        val instrumentation = ByteBuddyAgent.install()

        // The Kotlin fixtures under com.example.target reference kotlin-stdlib, a jar on the test
        // classpath the listing registers as a dependency, and java.lang, which the platform provides.
        val running =
            Agent.start(
                "includePackages=com.example.target,flushIntervalSeconds=3600,endpointsEnabled=false," +
                    "staticBaselineEnabled=true,exportUrl=http://localhost:${collector.address.port}",
                instrumentation,
            )
        try {
            assertNotNull(running)
            val references =
                received.get(30, TimeUnit.SECONDS).declaredClasses.flatMap { declared ->
                    declared.referencedClasses + declared.methods.flatMap { it.referencedClasses }
                }
            assertTrue(references.any { it.startsWith("kotlin.") }, "no reference into kotlin-stdlib was kept: $references")
            // A name the running JDK lacks (java.lang.MatchException below JDK 21) is an absent reference, not a JDK name.
            val platform = ClassLoader.getPlatformClassLoader()
            val jdkNames = references.filter { it.startsWith("java.") && platform.getResource(it.replace('.', '/') + ".class") != null }
            assertTrue(jdkNames.isEmpty(), "a JDK name was sent: $jdkNames")
            assertEquals(0, running.dependencyRegistry.classIndexSize, "the class index is released once the baseline is filtered")
        } finally {
            running?.stop()
            collector.stop(0)
        }
    }

    @Test
    fun `a listing that throws registers nothing, leaves the listing incomplete, and does not propagate`() {
        val registry = DependencyRegistry()

        Agent.runDependencyListing({ throw IllegalStateException("simulated listing failure") }, registry)

        assertFalse(registry.isListingComplete)
        assertTrue(registry.entries().isEmpty())
        assertEquals(DependencyRegistry.ListingOutcome.FAILED, registry.awaitListing(Duration.ofMillis(1)))
    }

    @Test
    fun `the agent's own jar is recorded as not a dependency, and a classes directory records nothing`(
        @TempDir dir: Path,
    ) {
        val jar = Files.write(dir.resolve("agent.jar"), ByteArray(0))
        val registry = DependencyRegistry()

        Agent.recordAgentJar(jar.toUri().toURL(), registry)
        Agent.recordAgentJar(dir.toUri().toURL(), registry)
        Agent.recordAgentJar(null, registry)

        assertTrue(registry.isJudgedNotADependency(DependencyOrigin.FlatJar(jar)))
        assertFalse(registry.isJudgedNotADependency(DependencyOrigin.FlatJar(dir)))
    }

    @Test
    fun `a listing that succeeds registers each dependency as found on the startup classpath`() {
        val registry = DependencyRegistry()
        val listed =
            ListedDependency(
                listOf(DependencyIdentity("g", "a", "1")),
                DependencyIdentitySource.POM_PROPERTIES,
                "/libs/a.jar",
                classCount = 2,
                origin = DependencyOrigin.FlatJar(Path.of("/libs/a.jar")),
                classNames = setOf("org.a.A"),
            )

        Agent.runDependencyListing({ listOf(listed) }, registry)

        assertTrue(registry.isListingComplete)
        val entry = registry.entries().single()
        assertEquals(DependencyDiscoverySource.STARTUP_CLASSPATH, entry.discoverySource)
        assertEquals(DependencyOrigin.FlatJar(Path.of("/libs/a.jar")), entry.origin)
        assertEquals(2, entry.classCount)
        assertEquals(null, registry.dependencyForClass("org.a.A"), "an unindexed registry keeps no class names")
    }

    @Test
    fun `a listing into an indexing registry indexes each dependency's class names`() {
        val registry = DependencyRegistry(indexClassNames = true)
        val listed =
            ListedDependency(
                listOf(DependencyIdentity("g", "a", "1")),
                DependencyIdentitySource.POM_PROPERTIES,
                "/libs/a.jar",
                classCount = 1,
                origin = DependencyOrigin.FlatJar(Path.of("/libs/a.jar")),
                classNames = setOf("org.a.A"),
            )

        Agent.runDependencyListing({ listOf(listed) }, registry)

        assertEquals(registry.entries().single().dependencyId, registry.dependencyForClass("org.a.A"))
    }

    @Test
    fun `filterEndpointModules drops the otel module unless otelBridgeEnabled, and keeps every other module either way`() {
        val otelModule = fakeEndpointModule("otel")
        val jdkModule = fakeEndpointModule("jdk-httpserver")
        val modules = listOf(jdkModule, otelModule)

        assertEquals(listOf(jdkModule), Agent.filterEndpointModules(modules, otelBridgeEnabled = false))
        assertEquals(listOf(jdkModule, otelModule), Agent.filterEndpointModules(modules, otelBridgeEnabled = true))
    }

    @Test
    fun `filterEndpointModules is a no-op when no module is named otel`() {
        val modules = listOf(fakeEndpointModule("jdk-httpserver"), fakeEndpointModule("spring-mvc"))

        assertEquals(modules, Agent.filterEndpointModules(modules, otelBridgeEnabled = false))
    }

    @Test
    fun `a module named in disabledEndpointModules is not installed and is reported CONFIGURED_OFF, and an unnamed one installs`() {
        val ktor = fakeEndpointModule("ktor-3")
        val jdk = fakeEndpointModule("jdk-httpserver")

        val selection =
            Agent.selectEndpointModules(
                listOf(jdk, ktor),
                endpointsEnabled = true,
                otelBridgeEnabled = false,
                disabledNames = listOf("ktor-3"),
            )

        assertEquals(listOf(jdk), selection.installed)
        assertEquals(listOf("ktor-3"), selection.keptOff.map { it.name })
        assertEquals("disabledEndpointModules names it", selection.keptOff.single().reason)
    }

    @Test
    fun `otel named in disabledEndpointModules with the bridge off is neither installed nor reported`() {
        val jdk = fakeEndpointModule("jdk-httpserver")
        val otel = fakeEndpointModule("otel")

        val selection =
            Agent.selectEndpointModules(
                listOf(jdk, otel),
                endpointsEnabled = true,
                otelBridgeEnabled = false,
                disabledNames = listOf("otel"),
            )

        assertEquals(listOf(jdk), selection.installed)
        assertEquals(emptyList(), selection.keptOff)
    }

    @Test
    fun `otel named in disabledEndpointModules with the bridge on is not installed and is reported`() {
        val jdk = fakeEndpointModule("jdk-httpserver")
        val otel = fakeEndpointModule("otel")

        val selection =
            Agent.selectEndpointModules(
                listOf(jdk, otel),
                endpointsEnabled = true,
                otelBridgeEnabled = true,
                disabledNames = listOf("otel"),
            )

        assertEquals(listOf(jdk), selection.installed)
        assertEquals(listOf("otel"), selection.keptOff.map { it.name })
    }

    @Test
    fun `an unknown disabledEndpointModules entry logs one WARNING naming it and the known modules, and changes nothing else`() {
        val jdk = fakeEndpointModule("jdk-httpserver")
        val ktor = fakeEndpointModule("ktor-3")
        lateinit var selection: Agent.EndpointModuleSelection

        val warnings =
            agentWarningsFrom {
                selection =
                    Agent.selectEndpointModules(
                        listOf(jdk, ktor),
                        endpointsEnabled = true,
                        otelBridgeEnabled = false,
                        disabledNames = listOf("ktor-9", "ktor-3"),
                    )
            }

        assertEquals(listOf(jdk), selection.installed)
        assertEquals(listOf("ktor-3"), selection.keptOff.map { it.name })
        val warning = warnings.single()
        assertTrue(warning.contains("'ktor-9'"), warning)
        assertTrue(warning.contains("jdk-httpserver") && warning.contains("ktor-3"), warning)
    }

    @Test
    fun `endpointsEnabled=false installs no module and reports every module that would have installed, the bridge only when enabled`() {
        val modules = listOf(fakeEndpointModule("jdk-httpserver"), fakeEndpointModule("ktor-3"), fakeEndpointModule("otel"))

        val bridgeOff =
            Agent.selectEndpointModules(
                modules,
                endpointsEnabled = false,
                otelBridgeEnabled = false,
                disabledNames = emptyList(),
            )
        val bridgeOn = Agent.selectEndpointModules(modules, endpointsEnabled = false, otelBridgeEnabled = true, disabledNames = emptyList())

        assertEquals(emptyList(), bridgeOff.installed)
        assertEquals(listOf("jdk-httpserver", "ktor-3"), bridgeOff.keptOff.map { it.name })
        assertTrue(bridgeOff.keptOff.all { it.reason == "endpointsEnabled=false" })
        assertEquals(emptyList(), bridgeOn.installed)
        assertEquals(listOf("jdk-httpserver", "ktor-3", "otel"), bridgeOn.keptOff.map { it.name })
    }

    @Test
    fun `a module kept off contributes no handler interfaces`() {
        val kept = fakeEndpointModule("ktor-3", handlerInterfaces = setOf("io.ktor.Handler"))
        val installed = fakeEndpointModule("jdk-httpserver", handlerInterfaces = setOf("com.sun.net.httpserver.HttpHandler"))

        val selection =
            Agent.selectEndpointModules(
                listOf(installed, kept),
                endpointsEnabled = true,
                otelBridgeEnabled = false,
                disabledNames = listOf("ktor-3"),
            )

        assertEquals(setOf("com.sun.net.httpserver.HttpHandler"), selection.installed.flatMapTo(sortedSetOf()) { it.handlerInterfaces })
    }

    @Test
    fun `an agent run with a module kept off sends it on the manifest as CONFIGURED_OFF`() {
        val manifests = CopyOnWriteArrayList<ProbeManifest>()
        val collector = HttpServer.create(InetSocketAddress("localhost", 0), 0)
        collector.createContext("/") { exchange ->
            val bytes = exchange.requestBody.readBytes()
            if (exchange.requestURI.path == "/v1/otherlode/manifest") manifests += ProtoPayloadCodec.decodeProbeManifest(bytes)
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        collector.start()
        var running: Agent.Running? = null
        val installLines =
            logMessagesFrom(EndpointInstrumentation::class.java.name, java.util.logging.Level.INFO) {
                running =
                    Agent.start(
                        "includePackages=dev.otherlode.neverloaded.fixture,flushIntervalSeconds=3600," +
                            "disabledEndpointModules=jdk-httpserver,exportUrl=http://localhost:${collector.address.port}",
                        ByteBuddyAgent.install(),
                    )
            }
        try {
            assertNotNull(running)
            val installing = installLines.single { it.startsWith("otherlode: installing endpoint modules:") }
            assertFalse(installing.contains("jdk-httpserver"), installing)
            running!!.scheduler.flush()
            val disabled = manifests.flatMap { it.disabledEndpointModules }.single()
            assertEquals("jdk-httpserver", disabled.module)
            assertEquals(DisabledEndpointModuleKind.CONFIGURED_OFF, disabled.kind)
            assertEquals("disabledEndpointModules names it", disabled.reason)
        } finally {
            running?.stop()
            collector.stop(0)
        }
    }

    @Test
    fun `endpointsEnabled=false sends every discovered module on the manifest as CONFIGURED_OFF`() {
        val manifests = CopyOnWriteArrayList<ProbeManifest>()
        val collector = HttpServer.create(InetSocketAddress("localhost", 0), 0)
        collector.createContext("/") { exchange ->
            val bytes = exchange.requestBody.readBytes()
            if (exchange.requestURI.path == "/v1/otherlode/manifest") manifests += ProtoPayloadCodec.decodeProbeManifest(bytes)
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        collector.start()
        val running =
            Agent.start(
                "includePackages=dev.otherlode.neverloaded.fixture,flushIntervalSeconds=3600,endpointsEnabled=false," +
                    "exportUrl=http://localhost:${collector.address.port}",
                ByteBuddyAgent.install(),
            )
        try {
            assertNotNull(running)
            assertNull(running.endpointTransformer)
            running.scheduler.flush()
            val disabled = manifests.flatMap { it.disabledEndpointModules }
            assertTrue(disabled.any { it.module == "jdk-httpserver" }, "$disabled")
            assertTrue(disabled.none { it.module == "otel" }, "$disabled")
            assertTrue(
                disabled.all { it.kind == DisabledEndpointModuleKind.CONFIGURED_OFF && it.reason == "endpointsEnabled=false" },
                "$disabled",
            )
        } finally {
            running?.stop()
            collector.stop(0)
        }
    }

    private fun agentWarningsFrom(block: () -> Unit): List<String> =
        logMessagesFrom(Agent::class.java.name, java.util.logging.Level.WARNING, block)

    private fun logMessagesFrom(
        loggerName: String,
        level: java.util.logging.Level,
        block: () -> Unit,
    ): List<String> {
        val records = mutableListOf<java.util.logging.LogRecord>()
        val handler =
            object : java.util.logging.Handler() {
                override fun publish(record: java.util.logging.LogRecord) {
                    records.add(record)
                }

                override fun flush() {}

                override fun close() {}
            }
        val julLogger =
            java.util.logging.Logger
                .getLogger(loggerName)
        val originalLevel = julLogger.level
        julLogger.addHandler(handler)
        julLogger.level = java.util.logging.Level.ALL
        try {
            block()
        } finally {
            julLogger.removeHandler(handler)
            julLogger.level = originalLevel
        }
        return records.filter { it.level == level }.mapNotNull { it.message }
    }
}

/** A minimal [EndpointModule] whose only meaningful behaviour is its [EndpointModule.name]. */
private fun fakeEndpointModule(
    moduleName: String,
    handlerInterfaces: Set<String> = emptySet(),
): EndpointModule =
    object : EndpointModule {
        override val name: String = moduleName
        override val handlerInterfaces: Set<String> = handlerInterfaces

        override fun typeMatcher(): ElementMatcher<in TypeDescription> = ElementMatchers.any()

        override fun transform(
            builder: DynamicType.Builder<*>,
            typeDescription: TypeDescription,
            advice: AdviceBinder,
            classLoader: ClassLoader?,
        ): DynamicType.Builder<*> = builder
    }
