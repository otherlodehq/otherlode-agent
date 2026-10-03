package dev.otherlode.instrumentation

import dev.otherlode.config.AgentConfig
import dev.otherlode.export.BodyKind
import dev.otherlode.export.KotlinKind
import dev.otherlode.export.ProbeKind
import dev.otherlode.export.ResourceAttributes
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
import net.bytebuddy.jar.asm.commons.ClassRemapper
import net.bytebuddy.jar.asm.commons.SimpleRemapper
import java.io.File
import java.lang.instrument.ClassFileTransformer
import java.lang.instrument.Instrumentation
import java.security.ProtectionDomain
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Handler
import java.util.logging.LogRecord
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.util.logging.Level as JulLevel
import java.util.logging.Logger as JulLogger

/**
 * Proves what happens to a method whose branches an earlier transformer changed. A transformer
 * registered ahead of Otherlode's swaps `BranchTarget`'s received bytes for a version whose
 * `classify` has two conditionals where the class file has one. The analysis reads the class file
 * and the rewrite walks the received bytes, so `classify`'s sites cannot be paired: it keeps its
 * METHOD probe and gets no branch probes, while `classifyDense` and `classifySparse`, unchanged,
 * keep theirs with the branch indexes the class file gives them.
 *
 * A second transformer removes a method the class file declares and adds one of its own, so the
 * two method sets differ: which methods get probes comes from the class file, and a method with no
 * body in the received bytes has nothing to weave into. A third only reorders the methods, so each
 * method's slots must land where the class file numbered them, not where the rewriter meets them.
 */
class BranchBytesCaptureTest {
    private val instrumentation: Instrumentation = ByteBuddyAgent.install()
    private var otherlode: OtherlodeInstrumentation? = null
    private var installedTransformer: ResettableClassFileTransformer? = null

    /** Stands in for an earlier agent: replaces BranchTarget's bytes with BranchTargetWithExtraBranches, renamed. */
    private val earlierAgent =
        object : ClassFileTransformer {
            override fun transform(
                loader: ClassLoader?,
                className: String?,
                classBeingRedefined: Class<*>?,
                protectionDomain: ProtectionDomain?,
                classfileBuffer: ByteArray,
            ): ByteArray? {
                if (className != "com/example/target/BranchTarget") return null
                val source = File("build/classes/java/test/com/example/target/BranchTargetWithExtraBranches.class").readBytes()
                val writer = ClassWriter(0)
                val remapper = SimpleRemapper("com/example/target/BranchTargetWithExtraBranches", "com/example/target/BranchTarget")
                ClassReader(source).accept(ClassRemapper(writer, remapper), 0)
                return writer.toByteArray()
            }
        }

    /**
     * Stands in for an earlier agent that changes the method set: drops `classifyDense`, which
     * nothing in the class calls, and adds `extra(I)I`, holding one conditional.
     */
    private val methodSetAgent =
        object : ClassFileTransformer {
            override fun transform(
                loader: ClassLoader?,
                className: String?,
                classBeingRedefined: Class<*>?,
                protectionDomain: ProtectionDomain?,
                classfileBuffer: ByteArray,
            ): ByteArray? {
                if (className != "com/example/target/BranchTarget") return null
                val writer = ClassWriter(0)
                val withoutDense =
                    object : ClassVisitor(Opcodes.ASM9, writer) {
                        override fun visitMethod(
                            access: Int,
                            name: String,
                            descriptor: String,
                            signature: String?,
                            exceptions: Array<out String>?,
                        ): MethodVisitor? =
                            if (name == "classifyDense") null else super.visitMethod(access, name, descriptor, signature, exceptions)

                        override fun visitEnd() {
                            val extra = super.visitMethod(Opcodes.ACC_PUBLIC, "extra", "(I)I", null, null)
                            extra.visitCode()
                            val nonPositive = Label()
                            extra.visitVarInsn(Opcodes.ILOAD, 1)
                            extra.visitJumpInsn(Opcodes.IFLE, nonPositive)
                            extra.visitInsn(Opcodes.ICONST_1)
                            extra.visitInsn(Opcodes.IRETURN)
                            extra.visitLabel(nonPositive)
                            extra.visitFrame(Opcodes.F_SAME, 0, null, 0, null)
                            extra.visitInsn(Opcodes.ICONST_0)
                            extra.visitInsn(Opcodes.IRETURN)
                            extra.visitMaxs(1, 2)
                            extra.visitEnd()
                            super.visitEnd()
                        }
                    }
                ClassReader(classfileBuffer).accept(withoutDense, 0)
                return writer.toByteArray()
            }
        }

    /**
     * Stands in for an earlier agent that only reorders methods: the received bytes declare
     * `BranchTarget`'s methods in reverse, so the rewriter meets them in a different order from the
     * class file's.
     */
    private val reorderingAgent =
        object : ClassFileTransformer {
            override fun transform(
                loader: ClassLoader?,
                className: String?,
                classBeingRedefined: Class<*>?,
                protectionDomain: ProtectionDomain?,
                classfileBuffer: ByteArray,
            ): ByteArray? {
                if (className != "com/example/target/BranchTarget") return null
                val reader = ClassReader(classfileBuffer)
                val writer = ClassWriter(0)
                val order = mutableListOf<Pair<String, String>>()
                // First pass: everything but the methods, noting their order.
                reader.accept(
                    object : ClassVisitor(Opcodes.ASM9, writer) {
                        override fun visitMethod(
                            access: Int,
                            name: String,
                            descriptor: String,
                            signature: String?,
                            exceptions: Array<out String>?,
                        ): MethodVisitor? {
                            order += name to descriptor
                            return null
                        }

                        override fun visitEnd() {}
                    },
                    0,
                )
                // Then each method on its own pass, last first.
                for (method in order.reversed()) {
                    reader.accept(
                        object : ClassVisitor(Opcodes.ASM9) {
                            override fun visitMethod(
                                access: Int,
                                name: String,
                                descriptor: String,
                                signature: String?,
                                exceptions: Array<out String>?,
                            ): MethodVisitor? =
                                if (name to descriptor == method) {
                                    writer.visitMethod(access, name, descriptor, signature, exceptions)
                                } else {
                                    null
                                }
                        },
                        0,
                    )
                }
                writer.visitEnd()
                return writer.toByteArray()
            }
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

    @AfterTest
    fun tearDown() {
        installedTransformer?.let { otherlode?.uninstall(instrumentation, it) }
        instrumentation.removeTransformer(earlierAgent)
        instrumentation.removeTransformer(methodSetAgent)
        instrumentation.removeTransformer(reorderingAgent)
    }

    @Test
    fun `methods an earlier transformer reordered count into the slots the class file numbered`() {
        val registry = ProbeRegistry()
        instrumentation.addTransformer(reorderingAgent, false)
        val otherlode = OtherlodeInstrumentation(AgentConfig.parse("includePackages=com.example.target"), registry)
        this.otherlode = otherlode
        installedTransformer = otherlode.install(instrumentation)

        val loader = FixtureClassLoader(arrayOf(File("build/classes/java/test").toURI().toURL()), javaClass.classLoader)
        val targetClass = Class.forName("com.example.target.BranchTarget", true, loader)
        val target = targetClass.getDeclaredConstructor().newInstance()
        targetClass.getMethod("classify", Int::class.java).invoke(target, -1)
        targetClass.getMethod("classifyDense", Int::class.java).invoke(target, 1)
        targetClass.getMethod("classifySparse", Int::class.java).invoke(target, 1000)

        val hits =
            registry
                .computeDeltaBatch(ResourceAttributes("test", null, "i-1", null, "run-1"))
                .batch.deltas
                .associate { it.probeIndex to it.hitsTotal }
        val probes =
            registry
                .manifest(ResourceAttributes("test", null, "instance-1", null, "run-1"))
                .probes
                .filter { it.className == "com.example.target.BranchTarget" && it.kind == ProbeKind.BRANCH }
        val countsOf = { method: String -> probes.filter { it.methodName == method }.map { it.branchIndex to (hits[it.probeIndex] ?: 0L) } }

        assertEquals(listOf(0 to 1L, 1 to 0L), countsOf("classify"), "-1 takes javac's IFLE")
        assertEquals(listOf(2 to 0L, 3 to 1L, 4 to 0L, 5 to 0L), countsOf("classifyDense"), "case 1 of cases 0, 1, 2 and the default")
        assertEquals(listOf(6 to 0L, 7 to 1L, 8 to 0L), countsOf("classifySparse"), "case 1000 of cases 1, 1000 and the default")
    }

    @Test
    fun `a method the received bytes lack gets no probe, and a method only they declare gets none either`() {
        val registry = LayoutRecordingRegistry()
        instrumentation.addTransformer(methodSetAgent, false)
        val otherlode = OtherlodeInstrumentation(AgentConfig.parse("includePackages=com.example.target"), registry)
        this.otherlode = otherlode
        installedTransformer = otherlode.install(instrumentation)

        val loader = FixtureClassLoader(arrayOf(File("build/classes/java/test").toURI().toURL()), javaClass.classLoader)
        val targetClass = Class.forName("com.example.target.BranchTarget", true, loader)
        val target = targetClass.getDeclaredConstructor().newInstance()
        assertEquals("non-positive", targetClass.getMethod("classify", Int::class.java).invoke(target, -1))
        assertEquals(201, targetClass.getMethod("classifySparse", Int::class.java).invoke(target, 1000))
        assertEquals(1, targetClass.getMethod("extra", Int::class.java).invoke(target, 5), "the added method runs")

        val manifest = registry.manifest(ResourceAttributes("test", null, "instance-1", null, "run-1"))
        val hits =
            registry
                .computeDeltaBatch(ResourceAttributes("test", null, "i-1", null, "run-1"))
                .batch.deltas
                .associate { it.probeIndex to it.hitsTotal }
        val probes = manifest.probes.filter { it.className == "com.example.target.BranchTarget" }

        assertTrue(probes.none { it.methodName == "classifyDense" }, "no probe of any kind for the removed method")
        assertTrue(probes.none { it.methodName == "extra" }, "no probe for the added method")
        val methodProbes = probes.filter { it.kind == ProbeKind.METHOD }
        assertEquals(listOf("<init>", "classify", "classifySparse"), methodProbes.map { it.methodName })
        assertEquals(1L, hits[methodProbes.single { it.methodName == "classify" }.probeIndex])
        assertEquals(1L, hits[methodProbes.single { it.methodName == "classifySparse" }.probeIndex])

        // Class-file branch indexes: classify 0 and 1, classifyDense's 2 to 5 left out, classifySparse 6 to 8.
        val classify = probes.filter { it.methodName == "classify" && it.kind == ProbeKind.BRANCH }
        assertEquals(listOf(0, 1), classify.map { it.branchIndex })
        assertEquals(listOf(1L, 0L), classify.map { hits[it.probeIndex] ?: 0L }, "-1 takes javac's IFLE")
        val sparse = probes.filter { it.methodName == "classifySparse" && it.kind == ProbeKind.BRANCH }
        assertEquals(listOf(6, 7, 8), sparse.map { it.branchIndex })
        assertEquals(listOf(0L, 1L, 0L), sparse.map { hits[it.probeIndex] ?: 0L })

        // The layout hash is built from exactly what the manifest lists, so the removed method's
        // signature and sites are not in it.
        val expectedLayout =
            ProbeLayoutHash.of(
                methodProbes.map { it.methodName + it.methodDescriptor } +
                    methodProbes.flatMap { method ->
                        method.branchSites.map {
                            "${method.methodName}${method.methodDescriptor}#branch${it.siteIndex}x${it.outcomes.size}"
                        }
                    },
            )
        assertEquals(expectedLayout, registry.layoutHashes["com.example.target.BranchTarget"])
    }

    @Test
    fun `a method whose branches an earlier transformer changed keeps its entry probe and gets no branch probes`() {
        val registry = ProbeRegistry()
        val config = AgentConfig.parse("includePackages=com.example.target")
        instrumentation.addTransformer(earlierAgent, false)
        val otherlode = OtherlodeInstrumentation(config, registry)
        this.otherlode = otherlode
        installedTransformer = otherlode.install(instrumentation)

        val loader = FixtureClassLoader(arrayOf(File("build/classes/java/test").toURI().toURL()), javaClass.classLoader)
        val (targetClass, records) =
            captureLogRecords(OtherlodeInstrumentation::class.java.name) {
                Class.forName("com.example.target.BranchTarget", true, loader)
            }
        val target = targetClass.getDeclaredConstructor().newInstance()
        val classify = targetClass.getMethod("classify", Int::class.java)
        assertEquals("large", classify.invoke(target, 500), "the received bytes are the ones that run")
        repeat(3) { classify.invoke(target, 5) }
        targetClass.getMethod("classifyDense", Int::class.java).invoke(target, 1)
        targetClass.getMethod("classifySparse", Int::class.java).invoke(target, 1000)

        val manifest = registry.manifest(ResourceAttributes("test", null, "instance-1", null, "run-1"))
        val hits =
            registry
                .computeDeltaBatch(ResourceAttributes("test", null, "i-1", null, "run-1"))
                .batch.deltas
                .associate { it.probeIndex to it.hitsTotal }
        val probes = manifest.probes.filter { it.className == "com.example.target.BranchTarget" }

        val classifyMethod = probes.single { it.methodName == "classify" && it.kind == ProbeKind.METHOD }
        assertEquals(4L, hits[classifyMethod.probeIndex], "the entry probe still counts every call")
        assertTrue(classifyMethod.branchSites.isEmpty(), "no site of an unpaired method is reported")
        assertTrue(probes.none { it.methodName == "classify" && it.kind == ProbeKind.BRANCH }, "nor any branch probe for it")

        // The class file numbers classify's one site as branch indexes 0 and 1, so classifyDense's
        // switch keeps 2 to 5 and classifySparse's 6 to 8 although classify's are left out.
        val dense = probes.filter { it.methodName == "classifyDense" && it.kind == ProbeKind.BRANCH }
        assertEquals(listOf(2, 3, 4, 5), dense.map { it.branchIndex })
        assertEquals(listOf(0L, 1L, 0L, 0L), dense.map { hits[it.probeIndex] ?: 0L }, "case 1 of cases 0, 1, 2 and the default")
        val sparse = probes.filter { it.methodName == "classifySparse" && it.kind == ProbeKind.BRANCH }
        assertEquals(listOf(6, 7, 8), sparse.map { it.branchIndex })
        assertEquals(listOf(0L, 1L, 0L), sparse.map { hits[it.probeIndex] ?: 0L }, "case 1000 of cases 1, 1000 and the default")

        val info = records.filter { it.level == JulLevel.INFO && it.message.contains("com.example.target.BranchTarget") }
        assertEquals(1, info.size, "one INFO line for the class")
        assertTrue(info.single().message.contains("classify(I)Ljava/lang/String;"), info.single().message)
    }

    /** Runs [block], returning its result and every log record [loggerName] emitted meanwhile. */
    private fun <T> captureLogRecords(
        loggerName: String,
        block: () -> T,
    ): Pair<T, List<LogRecord>> {
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
            return block() to records
        } finally {
            julLogger.removeHandler(handler)
            julLogger.level = originalLevel
        }
    }
}
