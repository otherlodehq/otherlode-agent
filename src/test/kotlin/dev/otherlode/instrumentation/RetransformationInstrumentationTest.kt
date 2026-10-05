package dev.otherlode.instrumentation

import dev.otherlode.config.AgentConfig
import dev.otherlode.export.BodyKind
import dev.otherlode.export.KotlinKind
import dev.otherlode.export.ProbeKind
import dev.otherlode.export.ProbeLocation
import dev.otherlode.export.ResourceAttributes
import dev.otherlode.instrumentation.branch.SizeGuard
import dev.otherlode.registry.ProbeMeta
import dev.otherlode.registry.ProbeRegistry
import net.bytebuddy.agent.ByteBuddyAgent
import net.bytebuddy.agent.builder.ResettableClassFileTransformer
import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.ClassWriter
import net.bytebuddy.jar.asm.FieldVisitor
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import java.io.File
import java.lang.instrument.ClassFileTransformer
import java.lang.instrument.Instrumentation
import java.security.ProtectionDomain
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Handler
import java.util.logging.LogRecord
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import java.util.logging.Level as JulLevel
import java.util.logging.Logger as JulLogger

/**
 * Proves that another agent's retransformation of a class this agent wove succeeds: the class is
 * woven again to the same bytes, keeps counting into the same probes, and nothing reaches the
 * registry a second time. A class this agent did not weave is left alone.
 *
 * Each "later agent" here is a retransformation-capable transformer registered after this agent's,
 * which is where the JVM puts any capable transformer an agent attaching later registers, the shape
 * of Arthas or BTrace.
 */
class RetransformationInstrumentationTest {
    private companion object {
        const val TARGET = "com.example.target"
        const val BRANCH_TARGET = "$TARGET.BranchTarget"
        val RESOURCE = ResourceAttributes("test", null, "instance-1", null, "run-1")
        val WOVEN_CLASSES =
            listOf("BranchTarget", "StaticInitTarget", "DefaultArgumentTarget", "GeneratedColour").map { "$TARGET.$it" }
    }

    private val instrumentation: Instrumentation = ByteBuddyAgent.install()
    private var installed: Pair<OtherlodeInstrumentation, ResettableClassFileTransformer>? = null
    private val laterAgents = mutableListOf<ClassFileTransformer>()

    @AfterTest
    fun tearDown() {
        laterAgents.forEach(instrumentation::removeTransformer)
        installed?.let { (otherlode, transformer) -> otherlode.uninstall(instrumentation, transformer) }
        installed = null
    }

    /** Counts how many times each class is registered. */
    private class CountingRegistry : ProbeRegistry() {
        val registrations = ConcurrentHashMap<String, AtomicInteger>()

        override fun register(
            className: String,
            layoutHash: Long,
            probes: List<ProbeMeta>,
            classLoader: ClassLoader?,
            superClassName: String?,
            interfaceNames: List<String>,
            classReferences: List<String>,
            sourceFile: String?,
            bodyKind: BodyKind,
            sourceName: String?,
            kotlinKind: KotlinKind,
        ): LongArray {
            registrations.computeIfAbsent(className) { AtomicInteger() }.incrementAndGet()
            return super.register(
                className,
                layoutHash,
                probes,
                classLoader,
                superClassName,
                interfaceNames,
                classReferences,
                sourceFile,
                bodyKind,
                sourceName,
                kotlinKind,
            )
        }
    }

    /**
     * A capable transformer registered after this agent's. It records the bytes it receives for
     * the fixtures, which are this agent's output. With [rewrite] it also changes a method body on
     * a retransformation, a change that leaves the class's fields and methods as they were.
     */
    private class LaterAgent(
        val rewrite: Boolean,
    ) : ClassFileTransformer {
        val received = ConcurrentHashMap<String, MutableList<ByteArray>>()

        override fun transform(
            loader: ClassLoader?,
            className: String?,
            classBeingRedefined: Class<*>?,
            protectionDomain: ProtectionDomain?,
            classfileBuffer: ByteArray,
        ): ByteArray? {
            if (className == null || !className.startsWith("com/example/target/")) return null
            received.computeIfAbsent(className) { mutableListOf() } += classfileBuffer
            if (!rewrite || classBeingRedefined == null || className != "com/example/target/BranchTarget") return null
            return replaceConstant(classfileBuffer, "positive", "POSITIVE")
        }
    }

    private fun install(
        config: AgentConfig,
        registry: ProbeRegistry,
        classFileCache: ClassFileByteCache = ClassFileByteCache(),
    ) {
        val otherlode = OtherlodeInstrumentation(config, registry, classFileCache = classFileCache)
        installed = otherlode to otherlode.install(instrumentation)
    }

    /** The fixture loader for a class woven in the field-and-accessor form, which is version 52 here, or the dynamic-constant form. */
    private fun fixtureLoader(legacy: Boolean): ClassLoader = if (legacy) LegacyFixtures.loader(javaClass.classLoader) else fixtureLoader()

    private fun fixtureLoader(): ClassLoader =
        FixtureClassLoader(
            arrayOf(File("build/classes/java/test").toURI().toURL(), File("build/classes/kotlin/test").toURI().toURL()),
            javaClass.classLoader,
        )

    private fun hitsOf(
        registry: ProbeRegistry,
        probe: ProbeLocation,
    ): Long =
        registry
            .computeDeltaBatch(RESOURCE)
            .batch.deltas
            .singleOrNull { it.classId == probe.classId && it.probeIndex == probe.probeIndex }
            ?.hitsTotal ?: 0L

    @Test
    fun `a retransformation with no other transformer re-weaves woven classes and keeps their counts`() {
        retransformWovenClasses(laterAgent = null, legacy = false)
    }

    @Test
    fun `a retransformation by an agent whose transformer returns null re-weaves woven classes to the same bytes`() {
        retransformWovenClasses(LaterAgent(rewrite = false), legacy = false)
    }

    @Test
    fun `a retransformation by an agent that changes a method body keeps that change and the probes counting`() {
        retransformWovenClasses(LaterAgent(rewrite = true), legacy = false)
    }

    @Test
    fun `a retransformation of version 52 classes with no other transformer re-weaves them and keeps their counts`() {
        retransformWovenClasses(laterAgent = null, legacy = true)
    }

    @Test
    fun `a retransformation of version 52 classes by an agent whose transformer returns null re-weaves them to the same bytes`() {
        retransformWovenClasses(LaterAgent(rewrite = false), legacy = true)
    }

    @Test
    fun `a retransformation of version 52 classes by an agent that changes a method body keeps that change and the probes counting`() {
        retransformWovenClasses(LaterAgent(rewrite = true), legacy = true)
    }

    /**
     * Loads and drives [WOVEN_CLASSES], retransforms them all in one batch, drives again, and checks
     * that the batch succeeded, the counts carried on, and the registry saw each class once. With
     * [legacy] the classes are version 52, so they carry the probe field and accessor; otherwise they
     * carry neither and load a dynamic constant.
     */
    private fun retransformWovenClasses(
        laterAgent: LaterAgent?,
        legacy: Boolean,
    ) {
        val registry = CountingRegistry()
        install(AgentConfig.parse("includePackages=$TARGET"), registry)
        laterAgent?.let {
            laterAgents += it
            instrumentation.addTransformer(it, true)
        }
        val loader = fixtureLoader(legacy)
        val classes = WOVEN_CLASSES.map { Class.forName(it, true, loader) }
        val branchTarget = classes.first()
        val target = branchTarget.getDeclaredConstructor().newInstance()
        val classify = branchTarget.getMethod("classify", Int::class.java)
        repeat(2) { classify.invoke(target, 5) }
        classify.invoke(target, -1)
        val before = registry.manifest(RESOURCE)

        val records =
            captureLogRecords(OtherlodeInstrumentation::class.java.name) { instrumentation.retransformClasses(*classes.toTypedArray()) }

        val expected = if (laterAgent?.rewrite == true) "POSITIVE" else "positive"
        assertEquals(expected, classify.invoke(target, 5), "the retransformed class runs, with the later agent's change")
        val after = registry.manifest(RESOURCE)
        assertEquals(before.probes, after.probes, "the manifest is unchanged")
        assertEquals(before.classLocations, after.classLocations)
        assertTrue(after.skippedClasses.isEmpty(), "${after.skippedClasses}")
        for (className in WOVEN_CLASSES) {
            assertEquals(1, registry.registrations[className]?.get(), "$className registered once")
        }
        assertTrue(records.none { it.level.intValue() >= JulLevel.INFO.intValue() }, "${records.map { it.message }}")

        val probes = after.probes.filter { it.className == "$TARGET.BranchTarget" && it.methodName == "classify" }
        assertEquals(4L, hitsOf(registry, probes.single { it.kind == ProbeKind.METHOD }), "three calls before and one after")
        assertEquals(
            listOf(1L, 3L),
            probes.filter { it.kind == ProbeKind.BRANCH }.sortedBy { it.branchIndex }.map { hitsOf(registry, it) },
            "-1 takes javac's IFLE, and 5 three times",
        )
        val typeInitializer =
            after.probes.single { it.className == "$TARGET.StaticInitTarget" && it.methodName == "<clinit>" }
        assertEquals(1L, hitsOf(registry, typeInitializer), "the type initializer does not run again")

        if (laterAgent != null) {
            for (className in WOVEN_CLASSES) {
                val received = assertNotNull(laterAgent.received[className.replace('.', '/')], "the later agent saw $className")
                assertEquals(2, received.size, "$className at load and at the retransformation")
                assertEquals(legacy, WovenBytes.declaresField(received[0]), "$className has the probe field only at version 52")
                assertEquals(!legacy, WovenBytes.loadsProbeConstant(received[0]), "$className loads the dynamic constant from version 55")
                assertTrue(received[0].contentEquals(received[1]), "$className is woven again to the same bytes")
            }
        }
    }

    @Test
    fun `a retransformation of a class this agent did not weave succeeds and changes nothing`() {
        val registry = CountingRegistry()
        val outOfScope = "$TARGET.SampleTarget"
        val alsoOutOfScope = "$TARGET.WeirdName"
        install(AgentConfig.parse("includePackages=$TARGET,excludePackages=$outOfScope;$alsoOutOfScope"), registry)
        val loader = fixtureLoader()
        val sample = Class.forName(outOfScope, true, loader)
        val weird = Class.forName(alsoOutOfScope, true, loader)
        val before = registry.manifest(RESOURCE)

        instrumentation.retransformClasses(sample, weird)

        assertEquals("pong", sample.getMethod("ping").invoke(sample.getDeclaredConstructor().newInstance()))
        assertEquals("hello", weird.getMethod("topLevelFunction").invoke(null))
        val after = registry.manifest(RESOURCE)
        assertTrue(registry.registrations.isEmpty(), "${registry.registrations}")
        assertEquals(before.skippedClasses, after.skippedClasses)
        assertTrue(after.skippedClasses.isEmpty(), "${after.skippedClasses}")
        assertFalse(WovenBytes.isWoven(classBytesAfterRetransform(sample)))
    }

    /** The bytes a capable transformer is handed for [cls] when it is retransformed. */
    private fun classBytesAfterRetransform(cls: Class<*>): ByteArray {
        var seen: ByteArray? = null
        val spy =
            object : ClassFileTransformer {
                override fun transform(
                    loader: ClassLoader?,
                    className: String?,
                    classBeingRedefined: Class<*>?,
                    protectionDomain: ProtectionDomain?,
                    classfileBuffer: ByteArray,
                ): ByteArray? {
                    if (classBeingRedefined === cls) seen = classfileBuffer
                    return null
                }
            }
        instrumentation.addTransformer(spy, true)
        try {
            instrumentation.retransformClasses(cls)
        } finally {
            instrumentation.removeTransformer(spy)
        }
        return assertNotNull(seen)
    }

    private fun captureLogRecords(
        loggerName: String,
        block: () -> Unit,
    ): List<LogRecord> {
        val records = mutableListOf<LogRecord>()
        val handler =
            object : Handler() {
                override fun publish(record: LogRecord) {
                    synchronized(records) { records += record }
                }

                override fun flush() {}

                override fun close() {}
            }
        val julLogger = JulLogger.getLogger(loggerName)
        val originalLevel = julLogger.level
        julLogger.addHandler(handler)
        julLogger.level = JulLevel.ALL
        try {
            block()
        } finally {
            julLogger.removeHandler(handler)
            julLogger.level = originalLevel
        }
        return synchronized(records) { records.toList() }
    }

    /**
     * Loads [BRANCH_TARGET] from a copy of its class file in a temporary directory, which a test may
     * then edit. The copy is version 52 unless [legacy] is false: a class woven in the field form is
     * the one whose redefinition the JVM refuses when the weave is refused.
     */
    private class EditableClassFile(
        legacy: Boolean = true,
        className: String = BRANCH_TARGET,
    ) {
        private val path = className.replace('.', '/') + ".class"
        val dir: File =
            java.nio.file.Files
                .createTempDirectory("otherlode-class-file")
                .toFile()
        val file = File(dir, path)

        init {
            file.parentFile.mkdirs()
            val source = File("build/classes/java/test/$path")
            if (legacy) LegacyFixtures.copyDowngraded(source, file) else source.copyTo(file)
        }

        /**
         * A loader that serves this class file and nothing else under its name: the parent's
         * classpath has the original, which a fallback would hand back once this one is deleted.
         */
        fun loader(parent: ClassLoader): ClassLoader =
            object : FixtureClassLoader(arrayOf(dir.toURI().toURL()), parent) {
                override fun getResource(name: String): java.net.URL? = if (name == path) findResource(name) else super.getResource(name)
            }
    }

    /** One [BRANCH_TARGET] method's entry count and its branch counts in branch-index order. */
    private fun countsOf(
        registry: ProbeRegistry,
        methodName: String,
    ): Pair<Long, List<Long>> {
        val probes = registry.manifest(RESOURCE).probes.filter { it.className == BRANCH_TARGET && it.methodName == methodName }
        return hitsOf(registry, probes.single { it.kind == ProbeKind.METHOD }) to
            probes.filter { it.kind == ProbeKind.BRANCH }.sortedBy { it.branchIndex }.map { hitsOf(registry, it) }
    }

    /**
     * Loads [BRANCH_TARGET], calls `classify` with 5 twice and -1 once and `classifyDense` with 1
     * once, and returns the class and an instance.
     */
    private fun loadAndDrive(loader: ClassLoader): Pair<Class<*>, Any> {
        val branchTarget = Class.forName(BRANCH_TARGET, true, loader)
        val target = branchTarget.getDeclaredConstructor().newInstance()
        val classify = branchTarget.getMethod("classify", Int::class.java)
        repeat(2) { classify.invoke(target, 5) }
        classify.invoke(target, -1)
        branchTarget.getMethod("classifyDense", Int::class.java).invoke(target, 1)
        return branchTarget to target
    }

    private fun assertRefusedWithWarning(
        className: String = BRANCH_TARGET,
        reason: String = "class file of $className differs from the one it was woven from",
        retransform: () -> Unit,
    ) {
        var refusal: Throwable? = null
        val records =
            captureLogRecords(OtherlodeInstrumentation::class.java.name) { refusal = runCatching(retransform).exceptionOrNull() }
        val thrown = refusal
        assertTrue(thrown is UnsupportedOperationException && "schema" in thrown.message.orEmpty(), "$thrown")
        assertTrue(
            records.any {
                it.level == JulLevel.WARNING && reason in it.message
            },
            "${records.map { it.message }}",
        )
    }

    /**
     * A class file edited on disk after the class loaded, keeping the layout hash: one conditional
     * now tests the opposite. Weaving it again would read the edited class file's polarity against
     * bytes that still have the old one, and count each outcome into the other's slot.
     */
    @Test
    fun `a retransformation after the class file was edited to invert a jump is refused, and counts stay on their outcomes`() {
        val registry = CountingRegistry()
        install(AgentConfig.parse("includePackages=$TARGET"), registry)
        val classFile = EditableClassFile()
        val (branchTarget, target) = loadAndDrive(classFile.loader(javaClass.classLoader))
        val before = registry.manifest(RESOURCE)
        classFile.file.writeBytes(
            replaceFirstJump(classFile.file.readBytes(), "classify") {
                if (it ==
                    Opcodes.IFLE
                ) {
                    Opcodes.IFGT
                } else {
                    Opcodes.IFLE
                }
            },
        )

        assertRefusedWithWarning { instrumentation.retransformClasses(branchTarget) }
        branchTarget.getMethod("classify", Int::class.java).invoke(target, 5)

        assertEquals(4L to listOf(1L, 3L), countsOf(registry, "classify"), "5 still counts on the positive outcome")
        assertEquals(before.probes, registry.manifest(RESOURCE).probes)
        assertEquals(1, registry.registrations[BRANCH_TARGET]?.get())
        assertTrue(registry.manifest(RESOURCE).skippedClasses.isEmpty())
    }

    @Test
    fun `a retransformation after the class file was edited so a method would not pair is refused`() {
        val registry = CountingRegistry()
        install(AgentConfig.parse("includePackages=$TARGET"), registry)
        val classFile = EditableClassFile()
        val (branchTarget, target) = loadAndDrive(classFile.loader(javaClass.classLoader))
        classFile.file.writeBytes(replaceFirstJump(classFile.file.readBytes(), "classify") { Opcodes.IFNE })

        assertRefusedWithWarning { instrumentation.retransformClasses(branchTarget) }
        branchTarget.getMethod("classify", Int::class.java).invoke(target, 5)
        branchTarget.getMethod("classifyDense", Int::class.java).invoke(target, 1)

        assertEquals(4L to listOf(1L, 3L), countsOf(registry, "classify"))
        assertEquals(2L to listOf(0L, 2L, 0L, 0L), countsOf(registry, "classifyDense"))
    }

    /**
     * A class woven from version 55 has no probe field whose absence would make the JVM reject
     * unwoven bytes, so a refused re-weave hands back the received bytes plus one field, which the
     * JVM rejects as a schema change. The class keeps running woven and counting, as one with a
     * probe field does.
     */
    @Test
    fun `a refused re-weave of a version 55 class is rejected by the JVM, and the class keeps counting`() {
        val registry = CountingRegistry()
        install(AgentConfig.parse("includePackages=$TARGET"), registry)
        val classFile = EditableClassFile(legacy = false)
        val (branchTarget, target) = loadAndDrive(classFile.loader(javaClass.classLoader))
        val before = registry.manifest(RESOURCE)
        classFile.file.writeBytes(replaceConstant(classFile.file.readBytes(), "positive", "POSITIVE"))

        assertRefusedWithWarning { instrumentation.retransformClasses(branchTarget) }

        assertEquals("positive", branchTarget.getMethod("classify", Int::class.java).invoke(target, 5), "the woven class still runs")
        assertEquals(4L to listOf(1L, 3L), countsOf(registry, "classify"), "the call after the refusal is counted")
        assertEquals(before.probes, registry.manifest(RESOURCE).probes)
        assertTrue(registry.manifest(RESOURCE).skippedClasses.isEmpty())
    }

    /**
     * The same for an interface: a field there must be public static final, so the marker is, or
     * ByteBuddy would reject it and the JVM accept the interface's unwoven bytes.
     */
    @Test
    fun `a refused re-weave of a version 55 interface is rejected by the JVM, and it keeps counting`() {
        val registry = CountingRegistry()
        install(AgentConfig.parse("includePackages=$TARGET"), registry)
        val name = "$TARGET.DefaultMethodTarget"
        val classFile = EditableClassFile(legacy = false, className = name)
        val target = Class.forName(name, true, classFile.loader(javaClass.classLoader))
        val staticThing = target.getMethod("staticThing")
        staticThing.invoke(null)
        classFile.file.writeBytes(replaceConstant(classFile.file.readBytes(), "static", "STATIC"))

        assertRefusedWithWarning(name) { instrumentation.retransformClasses(target) }

        assertEquals("static", staticThing.invoke(null), "the woven interface still runs")
        val probe =
            registry.manifest(RESOURCE).probes.single {
                it.className == name && it.methodName == "staticThing" &&
                    it.kind == ProbeKind.METHOD
            }
        assertEquals(2L, hitsOf(registry, probe), "the call after the refusal is counted")
    }

    /**
     * A redefinition shaped like an IDE's HotSwap, which writes the class file and then redefines
     * the class with it, of a class woven from version 55: the JVM rejects it as it does for a
     * retransformation.
     */
    @Test
    fun `a HotSwap-shaped redefinition of a version 55 class is refused, and the class keeps counting`() {
        val registry = CountingRegistry()
        install(AgentConfig.parse("includePackages=$TARGET"), registry)
        val classFile = EditableClassFile(legacy = false)
        val (branchTarget, target) = loadAndDrive(classFile.loader(javaClass.classLoader))
        val edited = replaceConstant(classFile.file.readBytes(), "positive", "POSITIVE")
        classFile.file.writeBytes(edited)

        assertRefusedWithWarning { instrumentation.redefineClasses(java.lang.instrument.ClassDefinition(branchTarget, edited)) }

        assertEquals("positive", branchTarget.getMethod("classify", Int::class.java).invoke(target, 5), "the woven class still runs")
        assertEquals(4L to listOf(1L, 3L), countsOf(registry, "classify"), "the call after the refusal is counted")
    }

    /**
     * The class file comparison of a re-weave reads the loader, not the byte cache: the cache holds
     * the class file as it was before the edit, because something looked the class up while another
     * was woven, and a comparison that read it would find the class unchanged and weave the edited
     * code on the old plan.
     */
    @Test
    fun `a HotSwap-shaped redefinition of a class whose old class file is cached is still refused`() {
        val registry = CountingRegistry()
        val cache = ClassFileByteCache()
        install(AgentConfig.parse("includePackages=$TARGET"), registry, cache)
        val classFile = EditableClassFile(legacy = false)
        val loader = classFile.loader(javaClass.classLoader)
        assertTrue(cache.locatorFor(loader).locate(BRANCH_TARGET).isResolved, "the old class file is cached")
        val (branchTarget, target) = loadAndDrive(loader)
        val edited = replaceConstant(classFile.file.readBytes(), "positive", "POSITIVE")
        classFile.file.writeBytes(edited)

        assertRefusedWithWarning { instrumentation.redefineClasses(java.lang.instrument.ClassDefinition(branchTarget, edited)) }

        assertEquals("positive", branchTarget.getMethod("classify", Int::class.java).invoke(target, 5), "the woven class still runs")
        assertEquals(4L to listOf(1L, 3L), countsOf(registry, "classify"), "the call after the refusal is counted")
    }

    /**
     * Bytes that arrive at a version whose probe form differs from the one the class was woven in,
     * with the class file unchanged: a class woven with a dynamic constant redefined at version 52,
     * where a dynamic constant is a `ClassFormatError`. The re-weave is refused, so the JVM rejects
     * the redefinition and the class keeps counting.
     */
    @Test
    fun `a redefinition at a version the class's probe form cannot follow is refused, and the class keeps counting`() {
        val registry = CountingRegistry()
        install(AgentConfig.parse("includePackages=$TARGET"), registry)
        val classFile = EditableClassFile(legacy = false)
        val (branchTarget, target) = loadAndDrive(classFile.loader(javaClass.classLoader))
        val downgraded = LegacyFixtures.downgraded(classFile.file.readBytes())

        assertRefusedWithWarning(reason = "arrive at class-file version 52, where it was woven at") {
            instrumentation.redefineClasses(java.lang.instrument.ClassDefinition(branchTarget, downgraded))
        }

        branchTarget.getMethod("classify", Int::class.java).invoke(target, 5)
        assertEquals(4L to listOf(1L, 3L), countsOf(registry, "classify"), "the call after the refusal is counted")
    }

    /**
     * A class that failed to weave, here one whose method is so near the class file's 64 KB limit
     * that its entry probe alone pushes it past, runs unwoven and is reported skipped; a later
     * retransformation leaves it alone.
     */
    @Test
    fun `a retransformation of a class that failed to weave succeeds and changes nothing`() {
        val registry = CountingRegistry()
        install(AgentConfig.parse("includePackages=$TARGET"), registry)
        val name = "$TARGET.TooLarge"
        val bytes = tooLargeToWeave(name.replace('.', '/'))
        val loader =
            object : ClassLoader(javaClass.classLoader) {
                override fun findClass(requested: String): Class<*> =
                    if (requested == name) defineClass(name, bytes, 0, bytes.size) else throw ClassNotFoundException(requested)

                override fun getResourceAsStream(resource: String): java.io.InputStream? =
                    if (resource == name.replace('.', '/') + ".class") bytes.inputStream() else super.getResourceAsStream(resource)
            }
        val type = Class.forName(name, true, loader)
        assertEquals(listOf(name), registry.manifest(RESOURCE).skippedClasses.map { it.className })

        instrumentation.retransformClasses(type)

        assertEquals(1, type.getMethod("big", Int::class.java).invoke(null, 200))
        assertEquals(listOf(name), registry.manifest(RESOURCE).skippedClasses.map { it.className }, "skipped once, not again")
        assertTrue(registry.registrations.isEmpty(), "${registry.registrations}")
        assertFalse(WovenBytes.isWoven(classBytesAfterRetransform(type)))
    }

    /**
     * A class whose `static int big(int)` is 6553 blocks of `if (x == k) y++`, 65534 bytes of code:
     * small enough to load and too large to write with even the entry probe, which the size guard
     * cannot drop.
     */
    private fun tooLargeToWeave(internalName: String): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null)
        cw.visitSource("TooLarge.java", null)
        cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "big", "(I)I", null, null).apply {
            visitCode()
            val start =
                net.bytebuddy.jar.asm
                    .Label()
            visitLabel(start)
            visitLineNumber(1, start)
            visitInsn(Opcodes.ICONST_0)
            visitVarInsn(Opcodes.ISTORE, 1)
            repeat(6553) { i ->
                val skip =
                    net.bytebuddy.jar.asm
                        .Label()
                visitVarInsn(Opcodes.ILOAD, 0)
                visitIntInsn(Opcodes.SIPUSH, 200 + i)
                visitJumpInsn(Opcodes.IF_ICMPNE, skip)
                visitIincInsn(1, 1)
                visitLabel(skip)
            }
            visitVarInsn(Opcodes.ILOAD, 1)
            visitInsn(Opcodes.IRETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    /**
     * An earlier capable agent that changes nothing at load and adds a branch to `classify` on a
     * retransformation, as a debugger adding a probe does. The bytes that arrive no longer pair in
     * `classify`, so its branch counts stop where they were while its entry probe and every other
     * method keep counting into their own slots.
     */
    @Test
    fun `an earlier agent that adds a branch on retransformation freezes that method's branch counts and nothing else`() {
        val registry = CountingRegistry()
        val earlier =
            object : ClassFileTransformer {
                override fun transform(
                    loader: ClassLoader?,
                    className: String?,
                    classBeingRedefined: Class<*>?,
                    protectionDomain: ProtectionDomain?,
                    classfileBuffer: ByteArray,
                ): ByteArray? =
                    if (classBeingRedefined != null && className == "com/example/target/BranchTarget") {
                        addLeadingBranch(classfileBuffer, "classify")
                    } else {
                        null
                    }
            }
        laterAgents += earlier
        instrumentation.addTransformer(earlier, true)
        install(AgentConfig.parse("includePackages=$TARGET"), registry)
        val (branchTarget, target) = loadAndDrive(fixtureLoader())

        val records =
            captureLogRecords(OtherlodeInstrumentation::class.java.name) {
                instrumentation.retransformClasses(branchTarget)
                instrumentation.retransformClasses(branchTarget)
            }
        branchTarget.getMethod("classify", Int::class.java).invoke(target, 5)
        branchTarget.getMethod("classifyDense", Int::class.java).invoke(target, 1)

        assertEquals(4L to listOf(1L, 2L), countsOf(registry, "classify"), "the entry probe counts; the branches stay")
        assertEquals(2L to listOf(0L, 2L, 0L, 0L), countsOf(registry, "classifyDense"))
        assertEquals(
            1,
            records.count { it.level == JulLevel.INFO && "classify(I)Ljava/lang/String;" in it.message },
            "one line for two retransformations: ${records.map { it.message }}",
        )
        assertEquals(1, registry.registrations[BRANCH_TARGET]?.get())
    }

    /**
     * An earlier capable agent that grows `classify` to 65510 bytes on retransformation. The first
     * weave kept its two branch probes, which fit; against the bytes that arrive now they would not
     * fit a class file, so the re-weave emits its sites unchanged. The entry probe still counts, the
     * branch counts stay where they were, and the retransformation succeeds.
     */
    @Test
    fun `an earlier agent that grows a method past the class file limit on retransformation freezes its branch counts`() {
        val registry = CountingRegistry()
        val earlier =
            object : ClassFileTransformer {
                override fun transform(
                    loader: ClassLoader?,
                    className: String?,
                    classBeingRedefined: Class<*>?,
                    protectionDomain: ProtectionDomain?,
                    classfileBuffer: ByteArray,
                ): ByteArray? =
                    if (classBeingRedefined != null && className == "com/example/target/BranchTarget") {
                        padMethod(classfileBuffer, "classify", 65510)
                    } else {
                        null
                    }
            }
        laterAgents += earlier
        instrumentation.addTransformer(earlier, true)
        install(AgentConfig.parse("includePackages=$TARGET"), registry)
        val (branchTarget, target) = loadAndDrive(fixtureLoader())
        val classify = branchTarget.getMethod("classify", Int::class.java)

        val records = captureLogRecords(OtherlodeInstrumentation::class.java.name) { instrumentation.retransformClasses(branchTarget) }
        repeat(3) { classify.invoke(target, 5) }

        assertEquals(
            6L to listOf(1L, 2L),
            countsOf(registry, "classify"),
            "the entry probe counts; the branches stay: ${records.map { it.message + it.thrown }}",
        )
        val warnings = records.filter { it.level == JulLevel.WARNING && "classify(I)Ljava/lang/String;" in it.message }
        assertEquals(1, warnings.size, "${records.map { it.message }}")
        assertTrue("would not fit a class file" in warnings.single().message, warnings.single().message)
    }

    /**
     * An earlier capable agent that inverts `classify`'s conditional the way JaCoCo does, testing the
     * opposite and jumping past a `goto` that carries the original edge, so the arriving jump's taken
     * edge is the class file's fall-through. With [atLoad] it does so at the first load too.
     */
    private class InvertingAgent(
        private val atLoad: Boolean,
    ) : ClassFileTransformer {
        override fun transform(
            loader: ClassLoader?,
            className: String?,
            classBeingRedefined: Class<*>?,
            protectionDomain: ProtectionDomain?,
            classfileBuffer: ByteArray,
        ): ByteArray? {
            if (className != "com/example/target/BranchTarget" || (classBeingRedefined == null && !atLoad)) return null
            return invertFirstJump(classfileBuffer, "classify")
        }
    }

    private fun retransformThroughInvertingAgent(atLoad: Boolean) {
        val registry = CountingRegistry()
        val earlier = InvertingAgent(atLoad)
        laterAgents += earlier
        instrumentation.addTransformer(earlier, true)
        install(AgentConfig.parse("includePackages=$TARGET"), registry)
        val (branchTarget, target) = loadAndDrive(fixtureLoader())
        val classify = branchTarget.getMethod("classify", Int::class.java)

        instrumentation.retransformClasses(branchTarget)
        repeat(3) { assertEquals("positive", classify.invoke(target, 5)) }
        assertEquals("non-positive", classify.invoke(target, -1))

        // Branch index 0 is the class file's taken edge, -1; branch index 1 its fall-through, 5.
        assertEquals(7L to listOf(2L, 5L), countsOf(registry, "classify"))
    }

    @Test
    fun `a jump an earlier agent inverts only on retransformation counts each outcome in its own slot`() {
        retransformThroughInvertingAgent(atLoad = false)
    }

    @Test
    fun `a jump an earlier agent inverts at load and on retransformation counts each outcome in its own slot`() {
        retransformThroughInvertingAgent(atLoad = true)
    }

    /**
     * A class defined from bytes, with no class file behind it, retransformed through an earlier
     * agent that changes a constant only then. Nothing about the code a re-weave relies on changed.
     */
    @Test
    fun `a class with no class file is woven again when an earlier agent changes it on retransformation`() {
        val registry = CountingRegistry()
        val earlier =
            object : ClassFileTransformer {
                override fun transform(
                    loader: ClassLoader?,
                    className: String?,
                    classBeingRedefined: Class<*>?,
                    protectionDomain: ProtectionDomain?,
                    classfileBuffer: ByteArray,
                ): ByteArray? =
                    if (classBeingRedefined != null && className == "com/example/target/BranchTarget") {
                        replaceConstant(classfileBuffer, "positive", "POSITIVE")
                    } else {
                        null
                    }
            }
        laterAgents += earlier
        instrumentation.addTransformer(earlier, true)
        install(AgentConfig.parse("includePackages=$TARGET"), registry)
        val bytes = File("build/classes/java/test/com/example/target/BranchTarget.class").readBytes()
        val (branchTarget, target) = loadAndDrive(BytesOnlyLoader(javaClass.classLoader, BRANCH_TARGET, bytes))

        instrumentation.retransformClasses(branchTarget)
        assertEquals("POSITIVE", branchTarget.getMethod("classify", Int::class.java).invoke(target, 5))

        assertEquals(4L to listOf(1L, 3L), countsOf(registry, "classify"))
    }

    /**
     * A class first woven from memory whose class file appears later. The plan holds no class-file
     * hash to compare it with, so its appearance says nothing about the code.
     */
    @Test
    fun `a class first woven from memory whose class file appears later is woven again`() {
        val registry = CountingRegistry()
        install(AgentConfig.parse("includePackages=$TARGET"), registry)
        val classFile = File("build/classes/java/test/com/example/target/BranchTarget.class")
        val loader = BytesOnlyLoader(javaClass.classLoader, BRANCH_TARGET, classFile.readBytes())
        val (branchTarget, target) = loadAndDrive(loader)
        loader.servedLater = classFile

        instrumentation.retransformClasses(branchTarget)
        branchTarget.getMethod("classify", Int::class.java).invoke(target, 5)

        assertEquals(4L to listOf(1L, 3L), countsOf(registry, "classify"))
    }

    /**
     * A re-weave uses the ordinals its first weave stored, not a fresh analysis, which would read
     * other classes' files. Here the `when` over an enum maps through `$WhenMappings`, whose class
     * file is replaced on disk with one whose initialiser maps nothing, while the class's own
     * class file is unchanged. Analysed now, four of the `when`s would get a different number of
     * slots than they were woven with.
     */
    @Test
    fun `a re-weave keeps the stored ordinals when a class file the analysis read has changed`() {
        val registry = CountingRegistry()
        install(AgentConfig.parse("includePackages=$TARGET"), registry)
        val dir =
            java.nio.file.Files
                .createTempDirectory("otherlode-when-mappings")
                .toFile()
        val packageDir = File(dir, "com/example/target").apply { mkdirs() }
        for (name in listOf("SwitchTarget", "SwitchTarget\$WhenMappings", "Tint")) {
            File("build/classes/kotlin/test/com/example/target/$name.class").copyTo(File(packageDir, "$name.class"))
        }
        File("build/classes/java/test/com/example/target/SwitchColor.class").copyTo(File(packageDir, "SwitchColor.class"))
        val loader =
            object : FixtureClassLoader(arrayOf(dir.toURI().toURL()), javaClass.classLoader) {
                override fun getResource(name: String): java.net.URL? =
                    if (name.startsWith("com/example/target/")) findResource(name) else super.getResource(name)
            }
        val switchTarget = Class.forName("$TARGET.SwitchTarget", true, loader)
        val tint = Class.forName("$TARGET.Tint", true, loader)
        val target = switchTarget.getDeclaredConstructor().newInstance()
        val drive = {
            for (method in listOf("enumWithElse", "enumStatement", "enumExhaustive")) {
                for (constant in tint.enumConstants) switchTarget.getMethod(method, tint).invoke(target, constant)
            }
            for (method in listOf("enumNullable", "enumWithNullBranch")) {
                for (constant in tint.enumConstants + null) switchTarget.getMethod(method, tint).invoke(target, constant)
            }
        }
        drive()
        val before = branchHitsOf(registry, "$TARGET.SwitchTarget")
        val mappings = File(packageDir, "SwitchTarget\$WhenMappings.class")
        mappings.writeBytes(withEmptyTypeInitializer(mappings.readBytes()))

        instrumentation.retransformClasses(switchTarget)
        drive()

        assertTrue(before.values.any { it > 0 })
        assertEquals(
            before.mapValues { (_, hits) ->
                2 * hits
            },
            branchHitsOf(registry, "$TARGET.SwitchTarget"),
            "each outcome counted twice",
        )
    }

    /** [className]'s branch counts, by method and branch index. */
    private fun branchHitsOf(
        registry: ProbeRegistry,
        className: String,
    ): Map<Pair<String, Int?>, Long> =
        registry
            .manifest(RESOURCE)
            .probes
            .filter { it.className == className && it.kind == ProbeKind.BRANCH }
            .associate { (it.methodName to it.branchIndex) to hitsOf(registry, it) }

    /** A class file that is gone by the time of a retransformation is no sign the code changed. */
    @Test
    fun `a retransformation after the class file was deleted is woven again`() {
        val registry = CountingRegistry()
        install(AgentConfig.parse("includePackages=$TARGET"), registry)
        val classFile = EditableClassFile()
        val (branchTarget, target) = loadAndDrive(classFile.loader(javaClass.classLoader))
        assertTrue(classFile.file.delete())

        instrumentation.retransformClasses(branchTarget)
        branchTarget.getMethod("classify", Int::class.java).invoke(target, 5)

        assertEquals(4L to listOf(1L, 3L), countsOf(registry, "classify"))
    }

    /**
     * An earlier agent that adds a class annotation whose `@Target` does not list a type, on a
     * retransformation. The bytes are legal to the JVM, and the agent weaves them again, so the
     * class keeps counting and is not recorded as skipped. The class is version 52, with the probe
     * field the unwoven bytes lack.
     */
    @Test
    fun `a woven class whose retransformed bytes carry a class annotation another agent added is woven again`() {
        val registry = CountingRegistry()
        val earlier =
            object : ClassFileTransformer {
                override fun transform(
                    loader: ClassLoader?,
                    className: String?,
                    classBeingRedefined: Class<*>?,
                    protectionDomain: ProtectionDomain?,
                    classfileBuffer: ByteArray,
                ): ByteArray? =
                    if (classBeingRedefined != null && className == "com/example/target/BranchTarget") {
                        annotateClass(classfileBuffer, "Ljava/lang/SafeVarargs;")
                    } else {
                        null
                    }
            }
        laterAgents += earlier
        instrumentation.addTransformer(earlier, true)
        install(AgentConfig.parse("includePackages=$TARGET"), registry)
        val (branchTarget, target) = loadAndDrive(fixtureLoader(legacy = true))

        val records =
            captureLogRecords(OtherlodeInstrumentation::class.java.name) { instrumentation.retransformClasses(branchTarget) }
        branchTarget.getMethod("classify", Int::class.java).invoke(target, 5)

        assertTrue(registry.manifest(RESOURCE).skippedClasses.isEmpty(), "${registry.manifest(RESOURCE).skippedClasses}")
        assertTrue(records.none { it.level.intValue() >= JulLevel.WARNING.intValue() }, "${records.map { it.message }}")
        assertEquals(4L to listOf(1L, 3L), countsOf(registry, "classify"))
    }

    /**
     * Defines one class from [bytes] and serves no class file for it, until [servedLater] is set:
     * from then on it serves that file as the class file.
     */
    private class BytesOnlyLoader(
        parent: ClassLoader,
        private val className: String,
        private val bytes: ByteArray,
    ) : ClassLoader(parent) {
        private val resourcePath = className.replace('.', '/') + ".class"

        @Volatile
        var servedLater: File? = null

        override fun loadClass(
            name: String,
            resolve: Boolean,
        ): Class<*> {
            if (name != className) return super.loadClass(name, resolve)
            synchronized(getClassLoadingLock(name)) {
                val loaded = findLoadedClass(name) ?: defineClass(name, bytes, 0, bytes.size)
                if (resolve) resolveClass(loaded)
                return loaded
            }
        }

        override fun getResourceAsStream(name: String): java.io.InputStream? =
            if (name == resourcePath) servedLater?.inputStream() else super.getResourceAsStream(name)

        override fun getResource(name: String): java.net.URL? =
            if (name == resourcePath) servedLater?.toURI()?.toURL() else super.getResource(name)
    }

    /**
     * An earlier capable agent that rewrites the class at load and skips a class being redefined:
     * on a retransformation the bytes that arrive differ from the ones the class was first woven
     * from, and it is woven again all the same.
     */
    @Test
    fun `an earlier agent that rewrites at load and skips retransformations does not break the retransformation`() {
        val registry = CountingRegistry()
        val earlier =
            object : ClassFileTransformer {
                override fun transform(
                    loader: ClassLoader?,
                    className: String?,
                    classBeingRedefined: Class<*>?,
                    protectionDomain: ProtectionDomain?,
                    classfileBuffer: ByteArray,
                ): ByteArray? =
                    if (classBeingRedefined == null && className == "com/example/target/BranchTarget") {
                        replaceConstant(classfileBuffer, "positive", "POSITIVE")
                    } else {
                        null
                    }
            }
        laterAgents += earlier
        instrumentation.addTransformer(earlier, true)
        install(AgentConfig.parse("includePackages=$TARGET"), registry)
        val (branchTarget, target) = loadAndDrive(fixtureLoader())
        val classify = branchTarget.getMethod("classify", Int::class.java)
        assertEquals("POSITIVE", classify.invoke(target, 5))

        instrumentation.retransformClasses(branchTarget)

        assertEquals("positive", classify.invoke(target, 5), "the earlier agent's change is gone, as it chose")
        assertEquals(5L to listOf(1L, 4L), countsOf(registry, "classify"))
        assertEquals(1, registry.registrations[BRANCH_TARGET]?.get())
    }
}

/** [bytes] with every `ldc` of [from] loading [to] instead; nothing else about the class changes. */
internal fun replaceConstant(
    bytes: ByteArray,
    from: String,
    to: String,
): ByteArray {
    val reader = ClassReader(bytes)
    val writer = ClassWriter(reader, 0)
    reader.accept(
        object : ClassVisitor(Opcodes.ASM9, writer) {
            override fun visitMethod(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                exceptions: Array<out String>?,
            ): MethodVisitor =
                object : MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, descriptor, signature, exceptions)) {
                    override fun visitLdcInsn(value: Any?) {
                        super.visitLdcInsn(if (value == from) to else value)
                    }
                }
        },
        0,
    )
    return writer.toByteArray()
}

/** [bytes] with the first `ifle` or `ifgt` in [methodName] replaced by the opcode [replace] gives for it. */
internal fun replaceFirstJump(
    bytes: ByteArray,
    methodName: String,
    replace: (Int) -> Int,
): ByteArray {
    val reader = ClassReader(bytes)
    val writer = ClassWriter(reader, 0)
    var replaced = false
    reader.accept(
        object : ClassVisitor(Opcodes.ASM9, writer) {
            override fun visitMethod(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                exceptions: Array<out String>?,
            ): MethodVisitor {
                val delegate = super.visitMethod(access, name, descriptor, signature, exceptions)
                if (name != methodName) return delegate
                return object : MethodVisitor(Opcodes.ASM9, delegate) {
                    override fun visitJumpInsn(
                        opcode: Int,
                        label: net.bytebuddy.jar.asm.Label,
                    ) {
                        if (!replaced && (opcode == Opcodes.IFLE || opcode == Opcodes.IFGT)) {
                            replaced = true
                            super.visitJumpInsn(replace(opcode), label)
                        } else {
                            super.visitJumpInsn(opcode, label)
                        }
                    }
                }
            }
        },
        0,
    )
    check(replaced) { "no ifle or ifgt in $methodName" }
    return writer.toByteArray()
}

/**
 * [bytes] with a conditional jump to the very next instruction added at the start of
 * [methodName], whose first parameter must be an `int`: one branch more than the class file has,
 * and no change to what the method does.
 */
internal fun addLeadingBranch(
    bytes: ByteArray,
    methodName: String,
): ByteArray {
    val reader = ClassReader(bytes)
    val writer = ClassWriter(reader, ClassWriter.COMPUTE_MAXS)
    reader.accept(
        object : ClassVisitor(Opcodes.ASM9, writer) {
            override fun visitMethod(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                exceptions: Array<out String>?,
            ): MethodVisitor {
                val delegate = super.visitMethod(access, name, descriptor, signature, exceptions)
                if (name != methodName) return delegate
                return object : MethodVisitor(Opcodes.ASM9, delegate) {
                    override fun visitCode() {
                        super.visitCode()
                        val next =
                            net.bytebuddy.jar.asm
                                .Label()
                        super.visitVarInsn(Opcodes.ILOAD, 1)
                        super.visitJumpInsn(Opcodes.IFLT, next)
                        super.visitLabel(next)
                        super.visitFrame(Opcodes.F_SAME, 0, null, 0, null)
                    }
                }
            }
        },
        0,
    )
    return writer.toByteArray()
}

/**
 * [bytes] with the first `ifle` or `ifgt` in [methodName] inverted the way JaCoCo inverts a jump:
 * the opposite test jumps past a `goto` that carries the original edge. Frames are recomputed.
 */
internal fun invertFirstJump(
    bytes: ByteArray,
    methodName: String,
): ByteArray {
    val reader = ClassReader(bytes)
    val writer = ClassWriter(ClassWriter.COMPUTE_FRAMES)
    var inverted = false
    reader.accept(
        object : ClassVisitor(Opcodes.ASM9, writer) {
            override fun visitMethod(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                exceptions: Array<out String>?,
            ): MethodVisitor {
                val delegate = super.visitMethod(access, name, descriptor, signature, exceptions)
                if (name != methodName) return delegate
                return object : MethodVisitor(Opcodes.ASM9, delegate) {
                    override fun visitJumpInsn(
                        opcode: Int,
                        label: net.bytebuddy.jar.asm.Label,
                    ) {
                        if (inverted || (opcode != Opcodes.IFLE && opcode != Opcodes.IFGT)) return super.visitJumpInsn(opcode, label)
                        inverted = true
                        val next =
                            net.bytebuddy.jar.asm
                                .Label()
                        super.visitJumpInsn(if (opcode == Opcodes.IFLE) Opcodes.IFGT else Opcodes.IFLE, next)
                        super.visitJumpInsn(Opcodes.GOTO, label)
                        super.visitLabel(next)
                    }
                }
            }
        },
        ClassReader.SKIP_FRAMES,
    )
    check(inverted) { "no ifle or ifgt in $methodName" }
    return writer.toByteArray()
}

/** [bytes] with a runtime-visible class annotation of [annotationDescriptor] added. */
internal fun annotateClass(
    bytes: ByteArray,
    annotationDescriptor: String,
): ByteArray {
    val reader = ClassReader(bytes)
    val writer = ClassWriter(reader, 0)
    reader.accept(
        object : ClassVisitor(Opcodes.ASM9, writer) {
            override fun visit(
                version: Int,
                access: Int,
                name: String,
                signature: String?,
                superName: String?,
                interfaces: Array<out String>?,
            ) {
                super.visit(version, access, name, signature, superName, interfaces)
                super.visitAnnotation(annotationDescriptor, true)?.visitEnd()
            }
        },
        0,
    )
    return writer.toByteArray()
}

/** [bytes] with a `<clinit>` that does nothing but return, every other member unchanged. */
internal fun withEmptyTypeInitializer(bytes: ByteArray): ByteArray {
    val reader = ClassReader(bytes)
    val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
    reader.accept(
        object : ClassVisitor(Opcodes.ASM9, writer) {
            override fun visitMethod(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                exceptions: Array<out String>?,
            ): MethodVisitor? {
                val delegate = super.visitMethod(access, name, descriptor, signature, exceptions)
                if (name != "<clinit>") return delegate
                delegate.visitCode()
                delegate.visitInsn(Opcodes.RETURN)
                delegate.visitMaxs(0, 0)
                delegate.visitEnd()
                return null
            }
        },
        ClassReader.SKIP_FRAMES,
    )
    return writer.toByteArray()
}

/** [bytes] with `nop`s at the start of [methodName] until its code is [length] bytes. */
internal fun padMethod(
    bytes: ByteArray,
    methodName: String,
    length: Int,
): ByteArray {
    val current =
        SizeGuard
            .codeLengths(bytes)
            .entries
            .single { it.key.first == methodName }
            .value
    val reader = ClassReader(bytes)
    val writer = ClassWriter(reader, 0)
    reader.accept(
        object : ClassVisitor(Opcodes.ASM9, writer) {
            override fun visitMethod(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                exceptions: Array<out String>?,
            ): MethodVisitor {
                val delegate = super.visitMethod(access, name, descriptor, signature, exceptions)
                if (name != methodName) return delegate
                return object : MethodVisitor(Opcodes.ASM9, delegate) {
                    override fun visitCode() {
                        super.visitCode()
                        repeat(length - current) { super.visitInsn(Opcodes.NOP) }
                    }
                }
            }
        },
        0,
    )
    return writer.toByteArray()
}
