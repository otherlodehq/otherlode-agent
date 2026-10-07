package dev.otherlode.instrumentation.endpoints

import dev.otherlode.export.DisabledEndpointModuleKind
import dev.otherlode.export.EndpointDiscoverySource
import dev.otherlode.instrumentation.BootstrapHolder
import dev.otherlode.instrumentation.endpoints.api.AdviceBinder
import dev.otherlode.instrumentation.endpoints.api.EndpointModule
import dev.otherlode.registry.EndpointRegistry
import net.bytebuddy.agent.ByteBuddyAgent
import net.bytebuddy.agent.builder.ResettableClassFileTransformer
import net.bytebuddy.asm.AsmVisitorWrapper
import net.bytebuddy.description.field.FieldDescription
import net.bytebuddy.description.field.FieldList
import net.bytebuddy.description.method.MethodList
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.DynamicType
import net.bytebuddy.implementation.Implementation
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.matcher.ElementMatcher
import net.bytebuddy.matcher.ElementMatchers.named
import net.bytebuddy.pool.TypePool
import java.io.File
import java.lang.instrument.ClassFileTransformer
import java.lang.instrument.Instrumentation
import java.security.ProtectionDomain
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EndpointInstrumentationTest {
    private var installedInstrumentation: Instrumentation? = null
    private var installedTransformer: ResettableClassFileTransformer? = null
    private var installedEndpointInstrumentation: EndpointInstrumentation? = null

    private fun frameworkLoader(): FrameworkFixtureClassLoader =
        FrameworkFixtureClassLoader(arrayOf(File("build/classes/java/test").toURI().toURL()), javaClass.classLoader)

    /**
     * Installs [EndpointInstrumentation] for real on the process-wide [Instrumentation], and only
     * then loads a fresh definition of the named fixture class, so the transformer is already
     * registered by the time the class first loads.
     */
    private fun install(
        registry: EndpointRegistry,
        modules: List<EndpointModule>,
        fixtureClassName: String,
    ): Any {
        val instrumentation = ByteBuddyAgent.install()
        installedInstrumentation = instrumentation
        val endpointInstrumentation = EndpointInstrumentation(registry, modules)
        installedEndpointInstrumentation = endpointInstrumentation
        installedTransformer = endpointInstrumentation.install(instrumentation)
        val loader = frameworkLoader()
        val fixtureClass = Class.forName(fixtureClassName, true, loader)
        return fixtureClass.getDeclaredConstructor().newInstance()
    }

    @AfterTest
    fun tearDown() {
        val instrumentation = installedInstrumentation
        val transformer = installedTransformer
        if (instrumentation != null && transformer != null) {
            installedEndpointInstrumentation?.uninstall(instrumentation, transformer)
        }
        installedInstrumentation = null
        installedTransformer = null
        installedEndpointInstrumentation = null
    }

    @Test
    fun `a route registered through addRoute is discovered by REGISTRATION with its handler class attached`() {
        val registry = EndpointRegistry()
        val router = install(registry, listOf(FakeRouterModule()), "com.example.framework.FakeRouter")
        val addRoute = router.javaClass.getMethod("addRoute", String::class.java, String::class.java, Runnable::class.java)
        val handler = Runnable {}

        addRoute.invoke(router, "GET", "/checkout", handler)

        val endpoint = registry.endpoints().single { it.verbatimTemplate == "/checkout" }
        assertEquals(EndpointDiscoverySource.REGISTRATION, endpoint.discoverySource)
        assertEquals("fake-router", endpoint.framework)
        assertEquals(handler.javaClass.name, endpoint.handlerClass)
    }

    @Test
    fun `a route added quietly and declared through publishRoutes is discovered by REGISTRATION with its handler class attached`() {
        val registry = EndpointRegistry()
        val router = install(registry, listOf(FakeRouterModule()), "com.example.framework.FakeRouter")
        val addQuietly = router.javaClass.getMethod("addQuietly", String::class.java, String::class.java, Runnable::class.java)
        val publishRoutes = router.javaClass.getMethod("publishRoutes")
        val handler = Runnable {}

        addQuietly.invoke(router, "GET", "/declared", handler)
        publishRoutes.invoke(router)

        val endpoint = registry.endpoints().single { it.verbatimTemplate == "/declared" }
        assertEquals(EndpointDiscoverySource.REGISTRATION, endpoint.discoverySource)
        assertEquals("fake-router", endpoint.framework)
        assertEquals(handler.javaClass.name, endpoint.handlerClass)
        assertTrue(registry.disabledModules().isEmpty())
    }

    @Test
    fun `two dispatches to one route give it hitsTotal 2, and the never-dispatched route nothing`() {
        val registry = EndpointRegistry()
        val router = install(registry, listOf(FakeRouterModule()), "com.example.framework.FakeRouter")
        val addRoute = router.javaClass.getMethod("addRoute", String::class.java, String::class.java, Runnable::class.java)
        val dispatch = router.javaClass.getMethod("dispatch", String::class.java, String::class.java)
        addRoute.invoke(router, "GET", "/hit", Runnable {})
        addRoute.invoke(router, "GET", "/never", Runnable {})

        dispatch.invoke(router, "GET", "/hit")
        dispatch.invoke(router, "GET", "/hit")

        val hitId = registry.endpoints().single { it.verbatimTemplate == "/hit" }.endpointId
        val neverId = registry.endpoints().single { it.verbatimTemplate == "/never" }.endpointId
        val byId = registry.computeDeltas(maxPerBatch = 10).flatMap { it.deltas }.associateBy { it.endpointId }

        assertEquals(2L, byId.getValue(hitId).hitsTotal)
        assertTrue(neverId !in byId)
    }

    @Test
    fun `a route added with addQuietly and then dispatched is discovered by DISPATCH with one hit`() {
        val registry = EndpointRegistry()
        val router = install(registry, listOf(FakeRouterModule()), "com.example.framework.FakeRouter")
        val addQuietly = router.javaClass.getMethod("addQuietly", String::class.java, String::class.java, Runnable::class.java)
        val dispatch = router.javaClass.getMethod("dispatch", String::class.java, String::class.java)
        addQuietly.invoke(router, "GET", "/hidden", Runnable {})

        dispatch.invoke(router, "GET", "/hidden")

        val endpoint = registry.endpoints().single { it.verbatimTemplate == "/hidden" }
        assertEquals(EndpointDiscoverySource.DISPATCH, endpoint.discoverySource)
        val delta = registry.computeDeltas(maxPerBatch = 10).flatMap { it.deltas }.single { it.endpointId == endpoint.endpointId }
        assertEquals(1L, delta.hitsTotal)
    }

    @Test
    fun `a broken module's advice failure disables it, and a second dispatch through it changes nothing`() {
        val registry = EndpointRegistry()
        val broken = install(registry, listOf(BrokenRouterModule()), "com.example.framework.BrokenRouter")
        val addRoute = broken.javaClass.getMethod("addRoute", String::class.java, String::class.java, Runnable::class.java)
        val dispatch = broken.javaClass.getMethod("dispatch", String::class.java, String::class.java)
        addRoute.invoke(broken, "GET", "/broken", Runnable {})

        dispatch.invoke(broken, "GET", "/broken")

        val disabled = registry.disabledModules().single { it.module == "broken-router" }
        assertTrue("simulated framework mismatch" in disabled.reason)
        assertEquals(DisabledEndpointModuleKind.LINKAGE_ERROR, disabled.kind, "the fixture advice throws a NoSuchMethodError")

        dispatch.invoke(broken, "GET", "/broken")

        assertEquals(1, registry.disabledModules().count { it.module == "broken-router" })
    }

    @Test
    fun `a module whose matcher throws leaves the class loadable and untouched`() {
        // A matcher that throws (a supertype walk hitting an unresolvable type, say) fails inside
        // ByteBuddy's own transform call, outside the module's try/catch, so the listener is the
        // only thing that sees it. The class must still define, with no advice woven.
        val throwingModule =
            object : EndpointModule {
                override val name: String = "throwing-matcher"

                override fun typeMatcher(): ElementMatcher<in TypeDescription> =
                    ElementMatcher { type ->
                        if (type.name == "com.example.framework.FakeRouter") throw IllegalStateException("simulated matcher failure")
                        false
                    }

                override fun transform(
                    builder: DynamicType.Builder<*>,
                    typeDescription: TypeDescription,
                    advice: AdviceBinder,
                    classLoader: ClassLoader?,
                ): DynamicType.Builder<*> = builder
            }
        val registry = EndpointRegistry()

        val router = install(registry, listOf(throwingModule), "com.example.framework.FakeRouter")
        val addRoute = router.javaClass.getMethod("addRoute", String::class.java, String::class.java, Runnable::class.java)
        addRoute.invoke(router, "GET", "/checkout", Runnable {})

        assertTrue(registry.endpoints().isEmpty(), "nothing was woven, so nothing registers")
    }

    @Test
    fun `a class a framework generated at runtime is never offered to a module`() {
        val seen = java.util.concurrent.CopyOnWriteArrayList<String>()
        val recordingModule =
            object : EndpointModule {
                override val name: String = "recording-${System.nanoTime()}"

                override fun typeMatcher(): ElementMatcher<in TypeDescription> =
                    ElementMatcher { type ->
                        type.name == "com.example.framework.FakeRouter" || type.name.startsWith("com.example.framework.FakeRouter\$\$")
                    }

                override fun transform(
                    builder: DynamicType.Builder<*>,
                    typeDescription: TypeDescription,
                    advice: AdviceBinder,
                    classLoader: ClassLoader?,
                ): DynamicType.Builder<*> {
                    seen += typeDescription.name
                    return builder
                }
            }
        val router = install(EndpointRegistry(), listOf(recordingModule), "com.example.framework.FakeRouter")

        // A Spring CGLIB proxy of the router: named after it, so a matcher on the name or on an
        // inherited annotation would take it, and woven twice would count every call twice.
        net.bytebuddy
            .ByteBuddy()
            .subclass(router.javaClass)
            .name("com.example.framework.FakeRouter\$\$SpringCGLIB\$\$0")
            .make()
            .load(router.javaClass.classLoader, net.bytebuddy.dynamic.loading.ClassLoadingStrategy.Default.INJECTION)

        assertEquals(listOf("com.example.framework.FakeRouter"), seen.toList())
    }

    @Test
    fun `EndpointModules discover finds the fake module registered as a service`() {
        val discovered = EndpointModules.discover(javaClass.classLoader)

        assertTrue(discovered.any { it.name == "fake-router" })
    }

    @Test
    fun `a module declaring a boot module needing the seam gets a real read edge installed`() {
        val registry = EndpointRegistry()
        install(registry, listOf(FakeRouterModule()), "com.example.framework.FakeRouter")

        val bootModule = ModuleLayer.boot().findModule("java.net.http").get()
        val seamModule = Class.forName(BootstrapHolder.ENDPOINTS_CLASS_NAME, false, null).module

        assertTrue(bootModule.canRead(seamModule))
    }

    /**
     * Drives the staging seam through a real transform, which the `PendingDeclarations` unit
     * tests cannot: they call begin/commit/discard by hand, so nothing there would notice if
     * [EndpointInstrumentation] stopped calling them.
     */
    @Test
    fun `an endpoint a module declares from its transform is registered once the class is woven`() {
        val registry = EndpointRegistry()
        install(registry, listOf(DeclaringModule()), "com.example.framework.FakeRouter")

        // This one would also pass with no staging at all. It is here to catch commit being
        // dropped from the listener, which would leave the endpoint staged and never declared.
        assertEquals("/declared-in-transform", registry.endpoints().single().verbatimTemplate)
        assertEquals(0, installedEndpointInstrumentation!!.pendingDeclarationCount(), "nothing is left staged on this thread")
    }

    @Test
    fun `a module whose transform throws is disabled as TRANSFORM_FAILED`() {
        val registry = EndpointRegistry()
        val declaring = DeclaringModule(throwAfterDeclaring = true)

        install(registry, listOf(declaring), "com.example.framework.FakeRouter")

        val disabled = registry.disabledModules().single { it.module == declaring.name }
        assertEquals(DisabledEndpointModuleKind.TRANSFORM_FAILED, disabled.kind)
        assertTrue("module gave up after declaring" in disabled.reason)
    }

    @Test
    fun `a LinkageError thrown by a transform is disabled as LINKAGE_ERROR`() {
        val registry = EndpointRegistry()
        val declaring = DeclaringModule(throwAfterDeclaring = true, failure = NoSuchMethodError("framework renamed it"))

        install(registry, listOf(declaring), "com.example.framework.FakeRouter")

        assertEquals(DisabledEndpointModuleKind.LINKAGE_ERROR, registry.disabledModules().single { it.module == declaring.name }.kind)
    }

    @Test
    fun `an endpoint declared by a transform whose rewrite fails is never registered`() {
        val registry = EndpointRegistry()
        val router = install(registry, listOf(DeclaringModule(failRewrite = true)), "com.example.framework.FakeRouter")

        assertTrue(registry.endpoints().isEmpty(), "the class never got its advice, so its routes are not declared")
        assertEquals(0, installedEndpointInstrumentation!!.pendingDeclarationCount())
        val routes = router.javaClass.getMethod("routes").invoke(router)
        assertTrue(routes is List<*> && routes.isEmpty(), "the class still loads and runs, from its original bytes")
    }

    @Test
    fun `a module that throws after declaring leaves none of its own endpoints behind`() {
        val registry = EndpointRegistry()
        install(registry, listOf(DeclaringModule(throwAfterDeclaring = true)), "com.example.framework.FakeRouter")

        // The lambda catches the throwable and hands the builder back, so the transform succeeds
        // and the listener commits. Without the rollback, the half-read route list lands anyway.
        assertTrue(registry.endpoints().isEmpty(), "a half-read route list is worse than none")
    }

    @Test
    fun `two modules matching one class both keep what they declared`() {
        val registry = EndpointRegistry()
        install(
            registry,
            listOf(
                DeclaringModule(template = "/first"),
                DeclaringModule(template = "/second"),
            ),
            "com.example.framework.FakeRouter",
        )

        assertEquals(
            setOf("/first", "/second"),
            registry.endpoints().mapTo(mutableSetOf()) { it.verbatimTemplate },
            "the second module's begin must not discard what the first staged",
        )
    }

    /**
     * Another agent's retransformation hands this tier the bytes from before it ran, so its advice
     * has to be woven in again or the class loses it. The module's transform runs a second time,
     * which also shows the transformer is retransformation-capable: the JVM calls no other kind on
     * a retransformation.
     */
    @Test
    fun `a retransformed class keeps counting through its advice and declares nothing again`() {
        val registry = EndpointRegistry()
        val declaring = DeclaringModule()
        val router = install(registry, listOf(FakeRouterModule(), declaring), "com.example.framework.FakeRouter")
        val addRoute = router.javaClass.getMethod("addRoute", String::class.java, String::class.java, Runnable::class.java)
        val dispatch = router.javaClass.getMethod("dispatch", String::class.java, String::class.java)
        addRoute.invoke(router, "GET", "/hit", Runnable {})
        dispatch.invoke(router, "GET", "/hit")

        installedInstrumentation!!.retransformClasses(router.javaClass)
        dispatch.invoke(router, "GET", "/hit")

        assertEquals(2, declaring.transforms.get(), "the module wove the retransformed class")
        assertEquals(setOf("/hit", "/declared-in-transform"), registry.endpoints().mapTo(mutableSetOf()) { it.verbatimTemplate })
        val hitId = registry.endpoints().single { it.verbatimTemplate == "/hit" }.endpointId
        val byId = registry.computeDeltas(maxPerBatch = 10).flatMap { it.deltas }.associateBy { it.endpointId }
        assertEquals(2L, byId.getValue(hitId).hitsTotal, "one dispatch before the retransformation and one after")
        assertTrue(registry.disabledModules().isEmpty())
        assertEquals(0, installedEndpointInstrumentation!!.pendingDeclarationCount())
    }

    @Test
    fun `a module that throws while weaving a retransformed class is not disabled`() {
        val registry = EndpointRegistry()
        val declaring = DeclaringModule(throwAfterDeclaring = true, throwOnlyAfterFirst = true)
        val router = install(registry, listOf(FakeRouterModule(), declaring), "com.example.framework.FakeRouter")
        val addRoute = router.javaClass.getMethod("addRoute", String::class.java, String::class.java, Runnable::class.java)
        val dispatch = router.javaClass.getMethod("dispatch", String::class.java, String::class.java)
        addRoute.invoke(router, "GET", "/hit", Runnable {})

        installedInstrumentation!!.retransformClasses(router.javaClass)
        dispatch.invoke(router, "GET", "/hit")

        assertEquals(2, declaring.transforms.get())
        assertTrue(registry.disabledModules().isEmpty(), "${registry.disabledModules()}")
        assertEquals(setOf("/hit", "/declared-in-transform"), registry.endpoints().mapTo(mutableSetOf()) { it.verbatimTemplate })
        val hitId = registry.endpoints().single { it.verbatimTemplate == "/hit" }.endpointId
        val byId = registry.computeDeltas(maxPerBatch = 10).flatMap { it.deltas }.associateBy { it.endpointId }
        assertEquals(1L, byId.getValue(hitId).hitsTotal, "the other module's advice was woven again")
    }

    /**
     * A module weaving a retransformed class is handed a description of the bytes passed in, which
     * are the bytes its advice goes into, not of the loaded class. Here an earlier agent adds an
     * annotation to a method on the retransformation, which only a description of those bytes
     * shows.
     */
    @Test
    fun `a module weaving a retransformed class sees the bytes passed in, not the loaded class`() {
        val instrumentation = ByteBuddyAgent.install()
        val earlier =
            object : ClassFileTransformer {
                override fun transform(
                    loader: ClassLoader?,
                    className: String?,
                    classBeingRedefined: Class<*>?,
                    protectionDomain: ProtectionDomain?,
                    classfileBuffer: ByteArray,
                ): ByteArray? =
                    if (classBeingRedefined != null && className == "com/example/framework/FakeRouter") {
                        annotateMethod(classfileBuffer, "routes", "Ljava/lang/Deprecated;")
                    } else {
                        null
                    }
            }
        val seen = mutableListOf<Boolean>()
        val observing =
            object : EndpointModule {
                override val name: String = "observing-${System.nanoTime()}"

                override fun typeMatcher(): ElementMatcher<in TypeDescription> = named("com.example.framework.FakeRouter")

                override fun transform(
                    builder: DynamicType.Builder<*>,
                    typeDescription: TypeDescription,
                    advice: AdviceBinder,
                    classLoader: ClassLoader?,
                ): DynamicType.Builder<*> {
                    val routes = typeDescription.declaredMethods.filter(named("routes")).only
                    seen += routes.declaredAnnotations.isAnnotationPresent(java.lang.Deprecated::class.java)
                    return builder
                }
            }
        instrumentation.addTransformer(earlier, true)
        try {
            val router = install(EndpointRegistry(), listOf(observing), "com.example.framework.FakeRouter")
            instrumentation.retransformClasses(router.javaClass)
        } finally {
            instrumentation.removeTransformer(earlier)
        }

        assertEquals(listOf(false, true), seen, "the second description is of the annotated bytes")
    }

    /**
     * A transformer that is not retransformation-capable runs before every one that is, so one
     * registered after this tier's still receives the bytes without the advice: the class file, or
     * a JaCoCo agent's output when the test JVM runs one.
     */
    @Test
    fun `a transformer registered after this tier that is not retransformation-capable runs before it`() {
        val instrumentation = ByteBuddyAgent.install()
        var received: ByteArray? = null
        val spy =
            object : ClassFileTransformer {
                override fun transform(
                    loader: ClassLoader?,
                    className: String?,
                    classBeingRedefined: Class<*>?,
                    protectionDomain: ProtectionDomain?,
                    classfileBuffer: ByteArray,
                ): ByteArray? {
                    if (className == "com/example/framework/FakeRouter") received = classfileBuffer
                    return null
                }
            }
        installedInstrumentation = instrumentation
        val endpointInstrumentation = EndpointInstrumentation(EndpointRegistry(), listOf(FakeRouterModule()))
        installedEndpointInstrumentation = endpointInstrumentation
        installedTransformer = endpointInstrumentation.install(instrumentation)
        instrumentation.addTransformer(spy, false)
        try {
            Class.forName("com.example.framework.FakeRouter", true, frameworkLoader())
        } finally {
            instrumentation.removeTransformer(spy)
        }

        val bytes = assertNotNull(received)
        val seam = "dev/otherlode/bootstrap/OtherlodeEndpoints".toByteArray()
        assertTrue((0..bytes.size - seam.size).none { at -> seam.indices.all { bytes[at + it] == seam[it] } }, "no advice yet")
        val underJacocoAgent =
            java.lang.management.ManagementFactory
                .getRuntimeMXBean()
                .inputArguments
                .any { it.startsWith("-javaagent:") && "jacoco" in it }
        if (!underJacocoAgent) {
            val classFile = File("build/classes/java/test/com/example/framework/FakeRouter.class").readBytes()
            assertTrue(bytes.contentEquals(classFile), "the spy received the class file")
        }
    }
}

/**
 * Declares one endpoint from inside its transform callback, the shape of a module that reads its
 * routes off a class's own annotations. [failRewrite] makes ByteBuddy's `make()` throw after the
 * callback returns; [throwAfterDeclaring] makes the callback itself throw once it has declared, on
 * every transform or, with [throwOnlyAfterFirst], on every transform after the first. Each
 * transform declares under its own key and template, so a second declaration would show as a
 * second endpoint.
 */
private class DeclaringModule(
    private val failRewrite: Boolean = false,
    private val throwAfterDeclaring: Boolean = false,
    private val throwOnlyAfterFirst: Boolean = false,
    // Unique per instance. A module this test disables through `moduleFailed` stays disabled for
    // the life of the JVM, and the test JVM is shared, so a fixed name would silence the module
    // for every later test that used it and make their assertions pass for the wrong reason.
    private val moduleName: String = "declaring-${System.nanoTime()}",
    private val template: String = "/declared-in-transform",
    private val failure: Throwable = IllegalStateException("module gave up after declaring"),
) : EndpointModule {
    override val name: String = moduleName

    /** How many times [transform] has run. */
    val transforms = AtomicInteger()

    override fun typeMatcher(): ElementMatcher<in TypeDescription> = named("com.example.framework.FakeRouter")

    override fun transform(
        builder: DynamicType.Builder<*>,
        typeDescription: TypeDescription,
        advice: AdviceBinder,
        classLoader: ClassLoader?,
    ): DynamicType.Builder<*> {
        val transform = transforms.incrementAndGet()
        dev.otherlode.bootstrap.OtherlodeEndpoints.register(
            moduleName,
            if (transform == 1) "$moduleName-key" else "$moduleName-key-$transform",
            "GET",
            if (transform == 1) template else "$template-$transform",
            null,
            typeDescription.name,
            null,
            null,
        )
        if (throwAfterDeclaring && (!throwOnlyAfterFirst || transform > 1)) throw failure
        return if (failRewrite) builder.visit(ThrowingAsmVisitorWrapper()) else builder
    }

    override fun declare(frameworkObject: Any) = Unit
}

/** Fails inside ByteBuddy's `make()`, after the transform callback has already returned. */
private class ThrowingAsmVisitorWrapper : AsmVisitorWrapper {
    override fun mergeWriter(flags: Int): Int = flags

    override fun mergeReader(flags: Int): Int = flags

    override fun wrap(
        instrumentedType: TypeDescription,
        classVisitor: ClassVisitor,
        implementationContext: Implementation.Context,
        typePool: TypePool,
        fields: FieldList<FieldDescription.InDefinedShape>,
        methods: MethodList<*>,
        writerFlags: Int,
        readerFlags: Int,
    ): ClassVisitor = throw IllegalStateException("rewrite refused these bytes")
}

/** [bytes] with a runtime-visible annotation of [annotationDescriptor] added to [methodName]. */
private fun annotateMethod(
    bytes: ByteArray,
    methodName: String,
    annotationDescriptor: String,
): ByteArray {
    val reader =
        net.bytebuddy.jar.asm
            .ClassReader(bytes)
    val writer =
        net.bytebuddy.jar.asm
            .ClassWriter(reader, 0)
    reader.accept(
        object : ClassVisitor(net.bytebuddy.jar.asm.Opcodes.ASM9, writer) {
            override fun visitMethod(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                exceptions: Array<out String>?,
            ): net.bytebuddy.jar.asm.MethodVisitor {
                val delegate = super.visitMethod(access, name, descriptor, signature, exceptions)
                if (name != methodName) return delegate
                delegate.visitAnnotation(annotationDescriptor, true)?.visitEnd()
                // Wrapped so ASM rewrites this method instead of copying its attributes verbatim.
                return object : net.bytebuddy.jar.asm.MethodVisitor(net.bytebuddy.jar.asm.Opcodes.ASM9, delegate) {}
            }
        },
        0,
    )
    return writer.toByteArray()
}
