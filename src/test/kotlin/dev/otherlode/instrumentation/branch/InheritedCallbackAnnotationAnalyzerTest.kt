package dev.otherlode.instrumentation.branch

import dev.otherlode.config.CallbackAnnotationName
import dev.otherlode.instrumentation.Relation
import net.bytebuddy.dynamic.ClassFileLocator
import net.bytebuddy.jar.asm.ClassWriter
import net.bytebuddy.jar.asm.Opcodes
import net.bytebuddy.utility.OpenedClassReader
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Proves which callback annotation [BranchSiteAnalyzer] says a method inherits from a method it
 * overrides, on javac output of the `inherit` fixtures. The annotation types carry the binary names
 * of the framework ones, since the list matches names. The `com.example.outside.inherit` package is
 * out of scope.
 */
class InheritedCallbackAnnotationAnalyzerTest {
    private companion object {
        const val PACKAGE = "com/example/target/inherit"
        const val EVENT_LISTENER = "org.springframework.context.event.EventListener"
        const val GET_MAPPING = "org.springframework.web.bind.annotation.GetMapping"
        const val MODEL_ATTRIBUTE = "org.springframework.web.bind.annotation.ModelAttribute"
        const val BEAN = "org.springframework.context.annotation.Bean"
        const val RUNTIME_CALL = "com.example.outside.named.RuntimeCall"
        const val CLASS_CALL = "com.example.outside.named.ClassCall"
        const val ACTIVITY_INTERFACE = "io.temporal.activity.ActivityInterface"
        const val COMMAND_HANDLER = "org.axonframework.commandhandling.CommandHandler"
        const val AXON_5_COMMAND_HANDLER = "org.axonframework.messaging.commandhandling.annotation.CommandHandler"
    }

    private val includePackages = listOf("com.example.target.inherit")

    private val reads = mutableListOf<String>()

    private fun fromBuildOutput(internalName: String): ByteArray? =
        ClassFileLocator
            .ForFolder(File("build/classes/java/test"))
            .locate(internalName.replace('/', '.'))
            .takeIf { it.isResolved }
            ?.resolve()
            ?: javaClass.classLoader.getResourceAsStream("$internalName.class")?.use { it.readBytes() }

    private val lookup: (String) -> ByteArray? = { internalName ->
        reads += internalName
        fromBuildOutput(internalName)
    }

    private fun analysisOf(
        className: String,
        configured: ConfiguredCallbackAnnotations = ConfiguredCallbackAnnotations.NONE,
        lookup: (String) -> ByteArray? = this.lookup,
        tableCache: BranchSiteAnalyzer.CrossClassTableCache? = null,
        bytes: ByteArray = checkNotNull(fromBuildOutput(className)),
    ): BranchSiteAnalyzer.Analysis =
        BranchSiteAnalyzer.analyzeThrough(
            OpenedClassReader.of(bytes),
            bytes,
            lookup,
            includePackages,
            emptyList(),
            tableCache,
            emptySet(),
            { null },
            null,
            outsideCallers = true,
            callbackAnnotations = configured,
            methodFilter = { _, _ -> true },
        )

    private fun inherited(
        simpleName: String,
        method: String,
        descriptor: String = "()V",
        configured: ConfiguredCallbackAnnotations = ConfiguredCallbackAnnotations.NONE,
    ): String? = analysisOf("$PACKAGE/$simpleName", configured).inheritedCallbackAnnotationOf(method, descriptor)

    private fun named(vararg dotted: String) =
        ConfiguredCallbackAnnotations(dotted.map { CallbackAnnotationName(it, it.replace('$', '.')) })

    @Test
    fun `an interface method, a superclass method and a default method pass their annotation to an unannotated override`() {
        assertEquals(GET_MAPPING, inherited("InheritImpl", "fromInterface", "()Ljava/lang/String;"))
        assertEquals(EVENT_LISTENER, inherited("InheritImpl", "fromSuperclass"))
        assertEquals(EVENT_LISTENER, inherited("InheritImpl", "fromDefault"))
    }

    @Test
    fun `an override with the descriptor of a generated interface method inherits with no bridge`() {
        assertEquals(GET_MAPPING, inherited("InheritImpl", "openApi", "()Lcom/example/target/inherit/Result;"))
    }

    @Test
    fun `a generic override inherits through its bridge`() {
        assertEquals(EVENT_LISTENER, inherited("InheritImpl", "handle", "(Ljava/lang/String;)V"))
        assertNull(inherited("InheritImpl", "handle", "(Ljava/lang/Object;)V"), "the bridge itself is not probed")
    }

    @Test
    fun `scheduling and lifecycle callbacks are never inherited`() {
        assertNull(inherited("InheritImpl", "scheduledInterface"))
        assertNull(inherited("InheritImpl", "scheduledSuper"))
        assertNull(inherited("InheritImpl", "postConstructSuper"))
    }

    @Test
    fun `Bean passes down from a superclass method and a default method, not an abstract one`() {
        assertEquals(BEAN, inherited("InheritImpl", "beanSuper", "()Ljava/lang/Object;"))
        assertEquals(BEAN, inherited("InheritImpl", "beanDefault", "()Ljava/lang/Object;"))
        assertNull(inherited("InheritImpl", "beanAbstract", "()Ljava/lang/Object;"))
    }

    @Test
    fun `the superclass chain is walked before the interfaces, then interfaces in declaration order`() {
        assertEquals(EVENT_LISTENER, inherited("FromSuperclassFirst", "m"))
        assertEquals(GET_MAPPING, inherited("FromFirstInterface", "m"))
        assertEquals(MODEL_ATTRIBUTE, inherited("FromSecondInterface", "m"))
    }

    @Test
    fun `a matching supertype method that passes nothing down does not end the walk`() {
        assertEquals(GET_MAPPING, inherited("SkipsUnpassable", "m"))
    }

    @Test
    fun `a method with an annotation of its own gets no inherited one, and its unannotated sibling does`() {
        val analysis = analysisOf("$PACKAGE/OwnWins")

        assertEquals(
            "org.springframework.scheduling.annotation.Scheduled",
            analysis.callbackAnnotationOf("fromInterface", "()Ljava/lang/String;"),
        )
        assertNull(analysis.inheritedCallbackAnnotationOf("fromInterface", "()Ljava/lang/String;"))
        assertEquals(GET_MAPPING, analysis.inheritedCallbackAnnotationOf("openApi", "()Lcom/example/target/inherit/Result;"))
    }

    @Test
    fun `an override of a base class's unannotated method inherits from the interface the base implements`() {
        assertEquals(GET_MAPPING, inherited("ConcreteController", "openApi", "()Lcom/example/target/inherit/Result;"))
        assertEquals(GET_MAPPING, inherited("BaseController", "openApi", "()Lcom/example/target/inherit/Result;"))
        assertNull(inherited("BaseController", "scheduledInterface"))
    }

    @Test
    fun `a never-inherited listed annotation does not pass down what it carries, on the method or through a composed one`() {
        assertNull(inherited("JmsImpl", "onMessage"))
        assertNull(inherited("JmsImpl", "composed"))
    }

    @Test
    fun `an out-of-scope supertype method passes its annotation down and its type is still the overridden one`() {
        val analysis = analysisOf("$PACKAGE/OutsideImpl")

        assertEquals(EVENT_LISTENER, analysis.inheritedCallbackAnnotationOf("listener", "()V"))
        assertEquals("com.example.outside.inherit.OutsideApi", analysis.overriddenOutsideTypeOf("listener", "()V"))
        assertNull(analysis.inheritedCallbackAnnotationOf("scheduled", "()V"))
        assertNull(analysis.inheritedCallbackAnnotationOf("plain", "()V"))
    }

    @Test
    fun `a JAX-RS annotation passes down to an unannotated method`() {
        assertEquals("jakarta.ws.rs.GET", inherited("RestImpl", "hello", "()Ljava/lang/String;"))
    }

    @Test
    fun `a method with a JAX-RS annotation of its own on the method inherits no JAX-RS annotation`() {
        assertNull(inherited("RestImpl", "withProduces", "()Ljava/lang/String;"))
    }

    @Test
    fun `a method with a JAX-RS annotation on a parameter only inherits no JAX-RS annotation`() {
        assertNull(inherited("RestImpl", "withParam", "(Ljava/lang/String;)Ljava/lang/String;"))
    }

    @Test
    fun `a family other than JAX-RS still passes down to a method that carries a JAX-RS annotation`() {
        assertEquals(EVENT_LISTENER, inherited("RestImpl", "mixed", "()Ljava/lang/String;"))
    }

    @Test
    fun `a ws rs package at any depth, in either namespace, counts as JAX-RS`() {
        assertTrue(MethodAnnotations(listOf("javax/ws/rs/Produces"), emptyList()).carriesJaxRs)
        assertTrue(MethodAnnotations(emptyList(), listOf("jakarta/ws/rs/container/Suspended")).carriesJaxRs)
        assertFalse(MethodAnnotations(listOf("org/example/ws/rs/Thing"), listOf("jakarta/ws/rsx/Thing")).carriesJaxRs)
    }

    @Test
    fun `a composed annotation passes down as the listed annotation it carries does`() {
        assertEquals("com.example.target.inherit.ComposedListener", inherited("ComposedImpl", "get", "()Ljava/lang/String;"))
        assertNull(inherited("ComposedImpl", "tick"))
    }

    @Test
    fun `a composed annotation that reaches several rules passes down over a relation any of them allows`() {
        val finder = CallbackAnnotationFinder(::mixedAnnotationBytes, null)

        assertEquals(
            "mixed/Both",
            finder.inheritable(listOf("mixed/Both"), emptyList(), Relation.INTERFACE_ABSTRACT, blockJaxRs = false),
        )
        assertNull(finder.inheritable(listOf("mixed/Both"), emptyList(), Relation.INTERFACE_ABSTRACT, blockJaxRs = true))
        assertEquals(
            "mixed/Both",
            finder.inheritable(listOf("mixed/Both"), emptyList(), Relation.SUPERCLASS, blockJaxRs = true),
        )
        assertEquals(
            "mixed/OnlyBean",
            finder.inheritable(listOf("mixed/OnlyBean"), emptyList(), Relation.INTERFACE_DEFAULT, blockJaxRs = true),
        )
        assertNull(finder.inheritable(listOf("mixed/OnlyBean"), emptyList(), Relation.INTERFACE_ABSTRACT, blockJaxRs = false))
    }

    /** `mixed/Both` carries Bean and a JAX-RS GET, and `mixed/OnlyBean` carries Bean alone. */
    private fun mixedAnnotationBytes(internalName: String): ByteArray? {
        val carried =
            when (internalName) {
                "mixed/Both" -> listOf("Lorg/springframework/context/annotation/Bean;", "Ljakarta/ws/rs/GET;")
                "mixed/OnlyBean" -> listOf("Lorg/springframework/context/annotation/Bean;")
                else -> return null
            }
        val writer = ClassWriter(0)
        writer.visit(
            Opcodes.V17,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT or Opcodes.ACC_ANNOTATION,
            internalName,
            null,
            "java/lang/Object",
            arrayOf("java/lang/annotation/Annotation"),
        )
        carried.forEach { writer.visitAnnotation(it, true).visitEnd() }
        writer.visitEnd()
        return writer.toByteArray()
    }

    @Test
    fun `a package-private supertype method passes down only within its package`() {
        assertNull(inherited("OtherPackageChild", "quiet"))
        assertEquals(EVENT_LISTENER, analysisOf("$PACKAGE/pp/SamePackageChild").inheritedCallbackAnnotationOf("quiet", "()V"))
    }

    @Test
    fun `a named annotation passes down from an interface method at either retention, and from a superclass method`() {
        val configured = named(RUNTIME_CALL, CLASS_CALL)

        assertEquals(RUNTIME_CALL, inherited("NamedImpl", "runtime", configured = configured))
        assertEquals(CLASS_CALL, inherited("NamedImpl", "classRetained", configured = configured))
        assertEquals(RUNTIME_CALL, inherited("NamedImpl", "fromSuperclass", configured = configured))
    }

    @Test
    fun `a named annotation passes down from an interface default method`() {
        val configured = named(RUNTIME_CALL)

        assertEquals(RUNTIME_CALL, inherited("NamedImpl", "defaultOnly", configured = configured))
    }

    @Test
    fun `a method with an annotation of its own inherits none, whatever its supertype carries`() {
        val analysis = analysisOf("$PACKAGE/NamedImpl", named(RUNTIME_CALL))

        assertNull(analysis.inheritedCallbackAnnotationOf("withDefault", "()V"))
        assertEquals("org.springframework.scheduling.annotation.Scheduled", analysis.callbackAnnotationOf("withDefault", "()V"))
    }

    @Test
    fun `with no name configured a named annotation on a supertype method passes nothing down`() {
        assertNull(inherited("NamedImpl", "runtime"))
        assertNull(inherited("NamedImpl", "classRetained"))
    }

    @Test
    fun `with no name configured a class-retention annotation on a supertype method is neither kept nor read`() {
        reads.clear()

        analysisOf("$PACKAGE/NamedImpl")

        assertEquals(emptyList(), reads.filter { it.endsWith("/ClassCall") })
    }

    @Test
    fun `a named annotation on a supertype method counts as seen, whether or not it labels the method`() {
        val configured = named(RUNTIME_CALL, CLASS_CALL)

        analysisOf("$PACKAGE/NamedImpl", configured)

        assertEquals(emptyList(), configured.unseen())
    }

    @Test
    fun `a named annotation on a supertype method of a method with a label of its own is still seen`() {
        val configured = named(RUNTIME_CALL)

        val analysis = analysisOf("$PACKAGE/SeenOnly", configured)

        assertNull(analysis.inheritedCallbackAnnotationOf("m", "()V"))
        assertEquals(emptyList(), configured.unseen())
    }

    @Test
    fun `with no name configured the walk stops at a method with a label of its own`() {
        reads.clear()

        analysisOf("$PACKAGE/SeenOnly")

        assertEquals(emptyList(), reads.filter { it.endsWith("/RuntimeCall") })
    }

    @Test
    fun `an answer for an annotation type is shared through the cache, so a second analysis reads no annotation type again`() {
        val cache = BranchSiteAnalyzer.CrossClassTableCache(100)
        analysisOf("$PACKAGE/ComposedImpl", tableCache = cache)
        reads.clear()

        analysisOf("$PACKAGE/ComposedImpl", tableCache = cache)

        assertEquals(emptyList(), reads.filter { it.endsWith("/ComposedListener") || it.endsWith("/ComposedScheduled") })
    }

    @Test
    fun `both questions of the walk share one visit of the supertypes, so each header is asked for once`() {
        val asked = mutableListOf<String>()
        val walk =
            OverrideWalk(includePackages, emptyList()) { name ->
                asked += name
                fromBuildOutput(name)?.let { TypeHeader.parse(it) }
            }
        val key = "fromInterface" to "()Ljava/lang/String;"
        val access = mapOf(key to Opcodes.ACC_PUBLIC)
        val callees = { _: Pair<String, String> -> emptySet<Pair<String, String>>() }
        val finder = CallbackAnnotationFinder({ null }, null)

        walk.overriddenTypes("$PACKAGE/InheritImpl", "$PACKAGE/InheritBase", listOf("$PACKAGE/InheritApi"), access, setOf(key), callees)
        val inheritedTypes =
            walk.inheritedAnnotations(
                "$PACKAGE/InheritImpl",
                "$PACKAGE/InheritBase",
                listOf("$PACKAGE/InheritApi"),
                access,
                setOf(key),
                OverrideWalk.OwnLabels({ null }, { false }),
                finder,
                callees,
            )

        assertEquals(GET_MAPPING, inheritedTypes[key])
        assertEquals(1, asked.count { it == "$PACKAGE/InheritApi" })
        assertEquals(1, asked.count { it == "$PACKAGE/InheritBase" })
    }

    @Test
    fun `a Kotlin default body inherits through its interface's method`() {
        val classes = defaultImplsClasses()
        val lookupWithSynthetic: (String) -> ByteArray? = { classes[it] ?: lookup(it) }

        val analysis =
            analysisOf(
                "$PACKAGE/Synthetic\$DefaultImpls",
                lookup = lookupWithSynthetic,
                bytes = classes.getValue("$PACKAGE/Synthetic\$DefaultImpls"),
            )

        assertEquals(GET_MAPPING, analysis.inheritedCallbackAnnotationOf("m", "(L$PACKAGE/Synthetic;)V"))
    }

    @Test
    fun `an activity interface's own default method is labelled by the interface annotation, and its static one is not`() {
        assertEquals(ACTIVITY_INTERFACE, inherited("ActApi", "withBody", "()Ljava/lang/String;"))
        assertNull(inherited("ActApi", "util", "()Ljava/lang/String;"))
    }

    @Test
    fun `an interface that is not an activity interface labels none of its own methods`() {
        assertNull(inherited("WfSelf", "signal"))
    }

    @Test
    fun `an unannotated sub-interface of an activity interface adds no activity methods`() {
        assertEquals(ACTIVITY_INTERFACE, inherited("ActMixedImpl", "plain", "()Ljava/lang/String;"))
        assertNull(inherited("ActMixedImpl", "extra", "()Ljava/lang/String;"))
    }

    @Test
    fun `a workflow annotation on a Kotlin default body counts, and a non-activity interface gives it no activity label`() {
        val classes = defaultImplsClasses(signalOnOwn = true)
        val lookupWithSynthetic: (String) -> ByteArray? = { classes[it] ?: lookup(it) }

        val analysis =
            analysisOf(
                "$PACKAGE/Synthetic\$DefaultImpls",
                lookup = lookupWithSynthetic,
                bytes = classes.getValue("$PACKAGE/Synthetic\$DefaultImpls"),
            )

        assertEquals("io.temporal.workflow.SignalMethod", analysis.callbackAnnotationOf("own", "(L$PACKAGE/Synthetic;)V"))
        assertNull(analysis.inheritedCallbackAnnotationOf("own", "(L$PACKAGE/Synthetic;)V"))
    }

    @Test
    fun `a workflow annotation on an interface's own default method labels it`() {
        assertEquals(
            "io.temporal.workflow.SignalMethod",
            analysisOf("$PACKAGE/WfSelf").callbackAnnotationOf("signal", "()V"),
        )
    }

    @Test
    fun `a Kotlin default body of an activity interface is labelled by the interface annotation`() {
        val classes = defaultImplsClasses(activityInterface = true)
        val lookupWithSynthetic: (String) -> ByteArray? = { classes[it] ?: lookup(it) }

        val analysis =
            analysisOf(
                "$PACKAGE/Synthetic\$DefaultImpls",
                lookup = lookupWithSynthetic,
                bytes = classes.getValue("$PACKAGE/Synthetic\$DefaultImpls"),
            )

        assertEquals(GET_MAPPING, analysis.inheritedCallbackAnnotationOf("m", "(L$PACKAGE/Synthetic;)V"))
        assertEquals(ACTIVITY_INTERFACE, analysis.inheritedCallbackAnnotationOf("own", "(L$PACKAGE/Synthetic;)V"))
    }

    /**
     * `Synthetic : NearA` redeclares `m()V`, and `Synthetic$DefaultImpls.m(LSynthetic;)V` holds its
     * default body, the shape kotlinc writes with `-jvm-default=disable`. With [activityInterface],
     * `Synthetic` carries `@ActivityInterface` and declares `own()V`, whose default body is in
     * `$DefaultImpls` too. With [signalOnOwn], `Synthetic` is not an activity interface, declares
     * `own()V`, and its default body carries `@SignalMethod`, as kotlinc copies it there.
     */
    private fun defaultImplsClasses(
        activityInterface: Boolean = false,
        signalOnOwn: Boolean = false,
    ): Map<String, ByteArray> {
        val interfaceName = "$PACKAGE/Synthetic"
        val iface = ClassWriter(0)
        iface.visit(
            Opcodes.V17,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT,
            interfaceName,
            null,
            "java/lang/Object",
            arrayOf("$PACKAGE/NearA"),
        )
        if (activityInterface) iface.visitAnnotation("Lio/temporal/activity/ActivityInterface;", true).visitEnd()
        if (activityInterface || signalOnOwn) {
            iface.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, "own", "()V", null, null).visitEnd()
        }
        iface.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, "m", "()V", null, null).visitEnd()
        iface.visitEnd()
        val impls = ClassWriter(0)
        impls.visit(Opcodes.V17, Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, "$interfaceName\$DefaultImpls", null, "java/lang/Object", null)
        for (name in if (activityInterface || signalOnOwn) listOf("m", "own") else listOf("m")) {
            impls.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, name, "(L$interfaceName;)V", null, null).apply {
                if (signalOnOwn && name == "own") visitAnnotation("Lio/temporal/workflow/SignalMethod;", true).visitEnd()
                visitCode()
                visitInsn(Opcodes.RETURN)
                visitMaxs(0, 1)
                visitEnd()
            }
        }
        impls.visitEnd()
        return mapOf(interfaceName to iface.toByteArray(), "$interfaceName\$DefaultImpls" to impls.toByteArray())
    }

    private fun assertInterfaceOnly(
        simpleName: String,
        method: String,
        expected: String,
        descriptor: String = "()V",
    ) {
        val analysis = analysisOf("$PACKAGE/$simpleName")
        assertEquals(expected, analysis.inheritedCallbackAnnotationOf(method, descriptor))
        assertNull(analysis.callbackAnnotationOf(method, descriptor))
    }

    @Test
    fun `a workflow annotation on an interface method labels the unannotated implementation`() {
        val prefix = "io.temporal.workflow"

        assertInterfaceOnly("WfImpl", "run", "$prefix.WorkflowMethod", "()Ljava/lang/String;")
        assertInterfaceOnly("WfImpl", "signal", "$prefix.SignalMethod")
        assertInterfaceOnly("WfImpl", "query", "$prefix.QueryMethod", "()Ljava/lang/String;")
        assertInterfaceOnly("WfImpl", "update", "$prefix.UpdateMethod")
        assertInterfaceOnly("WfImpl", "validateUpdate", "$prefix.UpdateValidatorMethod")
    }

    @Test
    fun `a workflow annotation passes down from an interface that is not a workflow interface, at any depth, abstract or default`() {
        assertInterfaceOnly("WfLooseImpl", "poke", "io.temporal.workflow.SignalMethod")
        assertInterfaceOnly("WfDeepImpl", "poke", "io.temporal.workflow.SignalMethod")
        assertInterfaceOnly("WfDeepImpl", "peek", "io.temporal.workflow.QueryMethod", "()Ljava/lang/String;")
    }

    @Test
    fun `a workflow annotation on the implementation method itself labels nothing`() {
        val analysis = analysisOf("$PACKAGE/WfOwn")

        assertNull(analysis.callbackAnnotationOf("run", "()V"))
        assertNull(analysis.inheritedCallbackAnnotationOf("run", "()V"))
    }

    @Test
    fun `a workflow annotation on a superclass method passes nothing down`() {
        val analysis = analysisOf("$PACKAGE/WfBaseChild")

        assertNull(analysis.callbackAnnotationOf("signal", "()V"))
        assertNull(analysis.inheritedCallbackAnnotationOf("signal", "()V"))
    }

    @Test
    fun `a composed annotation carrying a workflow annotation labels nothing`() {
        val analysis = analysisOf("$PACKAGE/WfComposedImpl")

        assertNull(analysis.callbackAnnotationOf("run", "()V"))
        assertNull(analysis.inheritedCallbackAnnotationOf("run", "()V"))
    }

    @Test
    fun `every method of an activity interface is labelled by the interface annotation, annotated or not`() {
        assertEquals(ACTIVITY_INTERFACE, inherited("ActImpl", "plain", "()Ljava/lang/String;"))
        assertEquals(ACTIVITY_INTERFACE, inherited("ActImpl", "withBody", "()Ljava/lang/String;"))
        assertNull(inherited("ActImpl", "notInInterface", "()Ljava/lang/String;"))
    }

    @Test
    fun `the methods of unannotated super-interfaces of an activity interface are labelled, at any depth`() {
        assertEquals(ACTIVITY_INTERFACE, inherited("ActChildImpl", "child", "()Ljava/lang/String;"))
        assertEquals(ACTIVITY_INTERFACE, inherited("ActChildImpl", "parent", "()Ljava/lang/String;"))
        assertEquals(ACTIVITY_INTERFACE, inherited("ActChildImpl", "grand", "()Ljava/lang/String;"))
    }

    @Test
    fun `an unannotated interface with no annotated descendant labels nothing`() {
        assertNull(inherited("ActLooseImpl", "loose", "()Ljava/lang/String;"))
    }

    @Test
    fun `a class that reaches an activity interface through its superclass is labelled`() {
        assertEquals(ACTIVITY_INTERFACE, inherited("ActViaSuper", "plain", "()Ljava/lang/String;"))
    }

    @Test
    fun `a method annotation that passes down is tried before the activity interface of the same supertype method`() {
        assertEquals("org.springframework.context.event.EventListener", inherited("ActAnnotatedImpl", "both", "()Ljava/lang/String;"))
    }

    @Test
    fun `an out-of-scope activity interface labels the override, which still names the overridden type`() {
        val analysis = analysisOf("$PACKAGE/ActOutsideImpl")

        assertEquals(ACTIVITY_INTERFACE, analysis.inheritedCallbackAnnotationOf("outside", "()Ljava/lang/String;"))
        assertEquals("com.example.outside.inherit.ActOutsideApi", analysis.overriddenOutsideTypeOf("outside", "()Ljava/lang/String;"))
    }

    @Test
    fun `a Temporal operation counts on the method itself and is not inherited, nor read through a composed annotation`() {
        val analysis = analysisOf("$PACKAGE/TemporalOpImpl")

        assertEquals("io.temporal.nexus.TemporalOperation", analysis.callbackAnnotationOf("own", "()V"))
        assertNull(analysis.callbackAnnotationOf("op", "()V"))
        assertNull(analysis.inheritedCallbackAnnotationOf("op", "()V"))
        assertNull(analysis.callbackAnnotationOf("composed", "()V"))
    }

    @Test
    fun `Axon 4 handler annotations label the method that carries them, through one level or two of meta-annotation`() {
        val analysis = analysisOf("$PACKAGE/AxonImpl")

        assertEquals(COMMAND_HANDLER, analysis.callbackAnnotationOf("command", "()V"))
        assertEquals("org.axonframework.modelling.saga.SagaEventHandler", analysis.callbackAnnotationOf("saga", "()V"))
        assertEquals("com.example.target.inherit.ComposedCommand", analysis.callbackAnnotationOf("composed", "()V"))
        assertEquals("org.axonframework.lifecycle.StartHandler", analysis.callbackAnnotationOf("start", "()V"))
    }

    @Test
    fun `an Axon 5 command handler labels the method that carries it, and so does an entity creator`() {
        val analysis = analysisOf("$PACKAGE/Axon5Impl")

        assertEquals(AXON_5_COMMAND_HANDLER, analysis.callbackAnnotationOf("command", "()V"))
        assertEquals("org.axonframework.eventsourcing.annotation.reflection.EntityCreator", analysis.callbackAnnotationOf("creator", "()V"))
    }

    @Test
    fun `an Axon handler passes down from an interface method, but a start handler does not`() {
        assertEquals("org.axonframework.eventhandling.EventHandler", inherited("AxonApiImpl", "event"))
        assertNull(inherited("AxonApiImpl", "start"))
        assertNull(inherited("AxonStartChild", "start"))
    }

    @Test
    fun `an Axon 5 command handler passes down from a superclass method, but an entity creator does not`() {
        assertEquals(AXON_5_COMMAND_HANDLER, inherited("Axon5Child", "command"))
        assertNull(inherited("Axon5Child", "creator"))
    }

    @Test
    fun `an own Axon annotation beats an inherited Temporal one`() {
        val analysis = analysisOf("$PACKAGE/AxonOverTemporal")

        assertEquals(COMMAND_HANDLER, analysis.callbackAnnotationOf("poke", "()V"))
        assertNull(analysis.inheritedCallbackAnnotationOf("poke", "()V"))
    }
}
