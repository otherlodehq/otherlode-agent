package dev.otherlode.instrumentation

import dev.otherlode.config.AgentConfig
import dev.otherlode.export.BodyKind
import dev.otherlode.export.GeneratedBy
import dev.otherlode.export.KotlinKind
import dev.otherlode.export.ProbeKind
import dev.otherlode.export.ProbeLocation
import dev.otherlode.export.ResourceAttributes
import dev.otherlode.export.RoutineKind
import dev.otherlode.instrumentation.branch.ScalaFixtures
import dev.otherlode.instrumentation.branch.SitePairing
import dev.otherlode.registry.ProbeMeta
import dev.otherlode.registry.ProbeRegistry
import net.bytebuddy.agent.ByteBuddyAgent
import net.bytebuddy.agent.builder.ResettableClassFileTransformer
import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.ClassWriter
import net.bytebuddy.jar.asm.Label
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import org.jacoco.core.data.ExecutionDataStore
import org.jacoco.core.data.SessionInfoStore
import org.jacoco.core.instr.Instrumenter
import org.jacoco.core.runtime.LoggerRuntime
import org.jacoco.core.runtime.OfflineInstrumentationAccessGenerator
import org.jacoco.core.runtime.RuntimeData
import java.io.File
import java.lang.instrument.ClassFileTransformer
import java.lang.instrument.Instrumentation
import java.nio.file.Files
import java.security.ProtectionDomain
import java.util.concurrent.ConcurrentHashMap
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
 * Proves that an instance with JaCoCo's instrumenter running ahead of this agent reports the same
 * manifest as one without: the shape is read from the class file, the probes are woven into the
 * received bytes JaCoCo left, and each inverted jump counts its outcomes on the class file's side.
 *
 * JaCoCo runs in process, as a transformer registered before Otherlode's, with its own runtime
 * started, so the instrumented fixtures run and JaCoCo's probes fire beside this agent's. When the
 * suite itself runs under JaCoCo's agent, the fixtures reach that transformer already instrumented,
 * which JaCoCo's instrumenter refuses; the agent's own instrumentation is then the earlier
 * transformer, in this run and in the run it is compared with.
 */
class EarlierTransformerInstrumentationTest {
    private companion object {
        const val TARGET = "com.example.target"
        const val SCALA_TARGET = "com.example.scalatarget"
        val RESOURCE = ResourceAttributes("test", null, "instance-1", null, "run-1")
    }

    private val instrumentation: Instrumentation = ByteBuddyAgent.install()
    private var installed: Pair<OtherlodeInstrumentation, ResettableClassFileTransformer>? = null
    private val earlierTransformers = mutableListOf<JacocoAhead>()

    @AfterTest
    fun tearDown() {
        uninstall()
        for (jacoco in earlierTransformers) {
            instrumentation.removeTransformer(jacoco)
            jacoco.shutdown()
        }
    }

    private fun uninstall() {
        installed?.let { (otherlode, transformer) -> otherlode.uninstall(instrumentation, transformer) }
        installed = null
    }

    /** JaCoCo's instrumenter, standing in for its agent, for the classes one loader defines. */
    private class JacocoAhead : ClassFileTransformer {
        private val runtime = LoggerRuntime()
        private val data = RuntimeData()
        private val instrumenter = Instrumenter(runtime)

        /** The bytes this transformer handed on, by internal name: JaCoCo's output, or what it was given. */
        val handedOn = ConcurrentHashMap<String, ByteArray>()

        @Volatile
        var loader: ClassLoader? = null

        init {
            runtime.startup(data)
        }

        override fun transform(
            loader: ClassLoader?,
            className: String?,
            classBeingRedefined: Class<*>?,
            protectionDomain: ProtectionDomain?,
            classfileBuffer: ByteArray,
        ): ByteArray? {
            if (loader == null || loader !== this.loader || className == null || classBeingRedefined != null) return null
            val instrumented =
                try {
                    instrumenter.instrument(classfileBuffer, className)
                } catch (_: Exception) {
                    // Already instrumented by a JaCoCo agent on this JVM; that output is the
                    // earlier transformer's then.
                    null
                }
            handedOn[className] = instrumented ?: classfileBuffer
            return instrumented
        }

        /** Whether any probe JaCoCo planted has fired. */
        fun anyProbeHit(): Boolean {
            val store = ExecutionDataStore()
            data.collect(store, SessionInfoStore(), false)
            return store.contents.any { it.hasHits() }
        }

        fun shutdown() = runtime.shutdown()
    }

    /** Remembers each class's layout hash as it is registered. */
    private class LayoutRecordingRegistry : ProbeRegistry() {
        val layoutHashes = ConcurrentHashMap<String, Long>()

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
            layoutHashes[className] = layoutHash
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

    private class Run(
        val registry: LayoutRecordingRegistry,
        val loader: ClassLoader,
        val jacoco: JacocoAhead?,
    ) {
        val probes: List<ProbeLocation> by lazy { registry.manifest(RESOURCE).probes }

        /** [className]'s probes with the per-registry class id cleared, so two runs compare. */
        fun probesOf(className: String): List<ProbeLocation> = probes.filter { it.className == className }.map { it.copy(classId = 0) }

        /** [probe]'s count so far, read from a delta batch that is never acknowledged. */
        fun hitsOf(probe: ProbeLocation): Long =
            registry
                .computeDeltaBatch(RESOURCE)
                .batch.deltas
                .singleOrNull { it.classId == probe.classId && it.probeIndex == probe.probeIndex }
                ?.hitsTotal ?: 0L
    }

    /**
     * Installs Otherlode, with JaCoCo ahead of it when [withJacoco] is true, loads and initialises
     * [classNames] through a fresh loader from [newLoader], runs [drive], and uninstalls again.
     */
    private fun run(
        withJacoco: Boolean,
        includePackage: String,
        classNames: List<String>,
        newLoader: () -> ClassLoader,
        drive: (ClassLoader) -> Unit = {},
    ): Run {
        val jacoco =
            if (withJacoco) {
                JacocoAhead().also {
                    earlierTransformers += it
                    instrumentation.addTransformer(it, false)
                }
            } else {
                null
            }
        val registry = LayoutRecordingRegistry()
        val otherlode = OtherlodeInstrumentation(AgentConfig.parse("includePackages=$includePackage"), registry)
        installed = otherlode to otherlode.install(instrumentation)
        try {
            val loader = newLoader()
            jacoco?.loader = loader
            for (name in classNames) Class.forName(name, true, loader)
            drive(loader)
            return Run(registry, loader, jacoco)
        } finally {
            uninstall()
            jacoco?.let { instrumentation.removeTransformer(it) }
        }
    }

    private fun targetLoader(): ClassLoader =
        FixtureClassLoader(
            arrayOf(File("build/classes/kotlin/test").toURI().toURL(), File("build/classes/java/test").toURI().toURL()),
            javaClass.classLoader,
        )

    private fun assertSameManifest(
        without: Run,
        with: Run,
        classNames: List<String>,
    ) {
        for (className in classNames) {
            val expected = without.probesOf(className)
            assertTrue(expected.isNotEmpty(), "$className was instrumented without JaCoCo")
            assertEquals(expected, with.probesOf(className), "$className's manifest with JaCoCo ahead")
            assertEquals(without.registry.layoutHashes[className], with.registry.layoutHashes[className], "$className's layout hash")
        }
        val classLocations = { run: Run ->
            run.registry
                .manifest(RESOURCE)
                .classLocations
                .map { it.copy(classId = 0) }
                .toSet()
        }
        assertEquals(classLocations(without), classLocations(with), "class locations")
    }

    @Test
    fun `the manifest of Kotlin and Java classes is the same with JaCoCo ahead`() {
        val classNames =
            listOf(
                "GeneratedPoint",
                "RoutineTarget",
                "SwitchTarget",
                "SwitchJavaTarget",
                "GuardTarget",
                "ConditionJavaTarget",
                "RoutineJavaTarget",
            ).map { "$TARGET.$it" }

        val without = run(false, TARGET, classNames, ::targetLoader)
        val with = run(true, TARGET, classNames, ::targetLoader)

        assertSameManifest(without, with, classNames)
        // Each family whose marks an earlier transformer's probes would hide is present, so the comparison is not vacuous.
        val probes = without.probes
        assertTrue(probes.any { it.className == "$TARGET.GeneratedPoint" && it.generatedBy == GeneratedBy.DATA_CLASS })
        val routines =
            probes
                .flatMap { it.branchSites }
                .flatMap { it.outcomes }
                .map { it.routine }
                .toSet()
        assertTrue(RoutineKind.FINALLY_COPY in routines, "a finally copy: $routines")
        assertTrue(RoutineKind.NULL_DEFAULT in routines, "a null-default path: $routines")
        // kotlinc's string when lists one outcome per literal and the default, leaving the hash
        // collision paths unprobed; javac's string switch is rebuilt with its literals.
        val stringWhen =
            probes.single {
                it.className == "$TARGET.SwitchTarget" && it.methodName == "stringWhen" &&
                    it.kind == ProbeKind.METHOD
            }
        assertEquals(6, stringWhen.branchSites.sumOf { it.outcomes.size }, "kotlinc's string when recognised")
        val stringStatement =
            probes.single {
                it.className == "$TARGET.SwitchJavaTarget" && it.methodName == "stringStatement" && it.kind == ProbeKind.METHOD
            }
        assertTrue(
            stringStatement.branchSites
                .single()
                .outcomes
                .any { it.caseLabel.isNotEmpty() },
            "javac's string switch rebuilt",
        )
    }

    @Test
    fun `the manifest of a Scala 3 case class and a string match is the same with JaCoCo ahead`() {
        val classNames = listOf("Cc", "Cc\$", "Switches").map { "$SCALA_TARGET.$it" }
        val newLoader = { ScalaFixtures.classLoader("scala3", javaClass.classLoader) }

        val without = run(false, SCALA_TARGET, classNames, newLoader)
        val with = run(true, SCALA_TARGET, classNames, newLoader)

        assertSameManifest(without, with, classNames)
        assertTrue(without.probes.any { it.className == "$SCALA_TARGET.Cc" && it.generatedBy == GeneratedBy.CASE_CLASS })
        val stringMatch = without.probes.single { it.methodName == "stringMatch" && it.kind == ProbeKind.METHOD }
        assertEquals(6, stringMatch.branchSites.sumOf { it.outcomes.size }, "scalac's string match recognised")
    }

    @Test
    fun `a jump JaCoCo inverts counts its hits on the class file's outcome, and JaCoCo's own probes still fire`() {
        val className = "$TARGET.RoutineTarget"
        val classFile = File("build/classes/kotlin/test/com/example/target/RoutineTarget.class").readBytes()
        val countMissing = "countMissing" to "([Ljava/lang/String;)I"
        val jacocoBytes = Instrumenter(OfflineInstrumentationAccessGenerator()).instrument(classFile, "com/example/target/RoutineTarget")
        assertTrue(
            SitePairing
                .of(
                    classFile,
                    jacocoBytes,
                    listOf(countMissing),
                ).swappedOrdinalsOf(countMissing.first, countMissing.second)
                .isNotEmpty(),
            "JaCoCo inverts at least one of countMissing's jumps, so the swap is exercised",
        )
        val drive = { loader: ClassLoader ->
            val target = Class.forName(className, true, loader)
            val instance = target.getDeclaredConstructor().newInstance()
            // No null in the array: the null test only ever goes one way.
            target.getMethod("countMissing", Array<String>::class.java).invoke(instance, arrayOf("a", "b", "c"))
            Unit
        }

        val without = run(false, TARGET, listOf(className), ::targetLoader, drive)
        val with = run(true, TARGET, listOf(className), ::targetLoader, drive)

        val hitsByBranch = { run: Run ->
            run.probes
                .filter { it.className == className && it.methodName == countMissing.first && it.kind == ProbeKind.BRANCH }
                .associate { it.branchIndex to run.hitsOf(it) }
        }
        val expected = hitsByBranch(without)
        assertEquals(expected, hitsByBranch(with), "each outcome counts the same hits with JaCoCo ahead")
        assertTrue(expected.values.any { it == 0L } && expected.values.any { it > 0L }, "driven one way: $expected")
        val jacoco = assertNotNull(with.jacoco)
        val instrumentedHere = jacoco.handedOn.values.any { !it.contentEquals(classFile) }
        assertTrue(instrumentedHere, "JaCoCo changed the bytes before this agent saw them")
        if (!isUnderJacocoAgent()) assertTrue(jacoco.anyProbeHit(), "JaCoCo's own probes fired beside this agent's")
    }

    /** Whether a JaCoCo agent was on this JVM's command line, which instruments the fixtures before [JacocoAhead] can. */
    private fun isUnderJacocoAgent(): Boolean =
        java.lang.management.ManagementFactory
            .getRuntimeMXBean()
            .inputArguments
            .any { it.startsWith("-javaagent:") && "jacoco" in it }

    @Test
    fun `a Java 8 interface given a type initializer by JaCoCo loads, counts its default method, and gets no type initializer probe`() {
        val dir = Files.createTempDirectory("otherlode-java8-interface").toFile()
        writeJava8Interface(dir)
        val interfaceName = "$TARGET.java8.Greeter"
        val newLoader = {
            FixtureClassLoader(arrayOf(dir.toURI().toURL()), javaClass.classLoader, "$TARGET.java8.")
        }
        val drive = { loader: ClassLoader ->
            val impl = Class.forName("$TARGET.java8.GreeterImpl", true, loader).getDeclaredConstructor().newInstance()
            val greet = Class.forName(interfaceName, false, loader).getMethod("greet", String::class.java)
            assertEquals("nobody", greet.invoke(impl, null))
            assertEquals("someone", greet.invoke(impl, "a"))
            Unit
        }

        val with = run(true, "$TARGET.java8", listOf(interfaceName), newLoader, drive)

        val handedOn = assertNotNull(with.jacoco?.handedOn?.get("com/example/target/java8/Greeter"))
        assertTrue(declaresTypeInitializer(handedOn), "JaCoCo gave the interface a <clinit> the class file lacks")
        val probes = with.probes.filter { it.className == interfaceName }
        val greet = probes.single { it.methodName == "greet" && it.kind == ProbeKind.METHOD }
        assertEquals(2L, with.hitsOf(greet), "the prelude filled the array before the default method ran")
        assertEquals(listOf(1L, 1L), probes.filter { it.kind == ProbeKind.BRANCH }.map { with.hitsOf(it) })
        assertFalse(probes.any { it.methodName == "<clinit>" }, "no <clinit> probe for a type initializer only JaCoCo wrote")
    }

    /**
     * Writes a Java 8 interface `Greeter` with one default method holding a null test, and a class
     * `GreeterImpl` implementing it, as class files under [dir]. ASM writes them rather than javac,
     * so the test does not depend on the running JDK's javac still accepting `--release 8`.
     */
    private fun writeJava8Interface(dir: File) {
        val greeter = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        greeter.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT,
            "com/example/target/java8/Greeter",
            null,
            "java/lang/Object",
            null,
        )
        greeter.visitSource("Greeter.java", null)
        val greet = greeter.visitMethod(Opcodes.ACC_PUBLIC, "greet", "(Ljava/lang/String;)Ljava/lang/String;", null, null)
        greet.visitCode()
        val start = Label()
        greet.visitLabel(start)
        greet.visitLineNumber(3, start)
        val named = Label()
        greet.visitVarInsn(Opcodes.ALOAD, 1)
        greet.visitJumpInsn(Opcodes.IFNONNULL, named)
        greet.visitLdcInsn("nobody")
        greet.visitInsn(Opcodes.ARETURN)
        greet.visitLabel(named)
        greet.visitLineNumber(4, named)
        greet.visitLdcInsn("someone")
        greet.visitInsn(Opcodes.ARETURN)
        greet.visitMaxs(0, 0)
        greet.visitEnd()
        greeter.visitEnd()

        val impl = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        impl.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC,
            "com/example/target/java8/GreeterImpl",
            null,
            "java/lang/Object",
            arrayOf("com/example/target/java8/Greeter"),
        )
        val init = impl.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
        init.visitCode()
        init.visitVarInsn(Opcodes.ALOAD, 0)
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
        init.visitInsn(Opcodes.RETURN)
        init.visitMaxs(0, 0)
        init.visitEnd()
        impl.visitEnd()

        val packageDir = File(dir, "com/example/target/java8").apply { mkdirs() }
        File(packageDir, "Greeter.class").writeBytes(greeter.toByteArray())
        File(packageDir, "GreeterImpl.class").writeBytes(impl.toByteArray())
    }

    private fun declaresTypeInitializer(bytes: ByteArray): Boolean {
        var found = false
        ClassReader(bytes).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor? {
                    if (name == "<clinit>") found = true
                    return null
                }
            },
            ClassReader.SKIP_CODE,
        )
        return found
    }

    @Test
    fun `a class defined from bytes with no class file behind it is analysed from its received bytes`() {
        val bytes = File("build/classes/java/test/com/example/target/BranchTarget.class").readBytes()
        val registry = ProbeRegistry()
        val otherlode = OtherlodeInstrumentation(AgentConfig.parse("includePackages=$TARGET"), registry)
        installed = otherlode to otherlode.install(instrumentation)
        val loader = BytesOnlyClassLoader(javaClass.classLoader, "$TARGET.BranchTarget", bytes)

        val records =
            captureLogRecords(OtherlodeInstrumentation::class.java.name) {
                val target = Class.forName("$TARGET.BranchTarget", true, loader)
                target.getMethod("classify", Int::class.java).invoke(target.getDeclaredConstructor().newInstance(), 5)
            }

        val probes = registry.manifest(RESOURCE).probes.filter { it.className == "$TARGET.BranchTarget" }
        val classify = probes.single { it.methodName == "classify" && it.kind == ProbeKind.METHOD }
        assertEquals(7, classify.line, "the received bytes carry the line table")
        assertEquals(1, classify.branchSites.size, "and the branch site")
        assertEquals(2, probes.count { it.methodName == "classify" && it.kind == ProbeKind.BRANCH })
        assertTrue(records.none { it.level == JulLevel.WARNING }, "the received bytes were enough: ${records.map { it.message }}")
        assertTrue(
            records.any { it.level == JulLevel.FINE && "no class file" in it.message && "$TARGET.BranchTarget" in it.message },
            "one FINE line says the class file was missing",
        )
    }

    /** Defines one class from [bytes] and serves no class file for it. */
    private class BytesOnlyClassLoader(
        parent: ClassLoader,
        private val className: String,
        private val bytes: ByteArray,
    ) : ClassLoader(parent) {
        private val resourcePath = className.replace('.', '/') + ".class"

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

        override fun getResourceAsStream(name: String) = if (name == resourcePath) null else super.getResourceAsStream(name)

        override fun getResource(name: String) = if (name == resourcePath) null else super.getResource(name)
    }

    private fun captureLogRecords(
        loggerName: String,
        block: () -> Unit,
    ): List<LogRecord> {
        val records = mutableListOf<LogRecord>()
        val handler =
            object : Handler() {
                override fun publish(record: LogRecord) {
                    records += record
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
        return records
    }
}
