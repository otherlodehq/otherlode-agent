package dev.otherlode.benchmark

import dev.otherlode.config.AgentConfig
import dev.otherlode.export.ProbeKind
import dev.otherlode.export.ResourceAttributes
import dev.otherlode.instrumentation.OtherlodeInstrumentation
import dev.otherlode.instrumentation.branch.BranchSiteAnalyzer
import dev.otherlode.instrumentation.branch.SizeGuard
import dev.otherlode.registry.ProbeRegistry
import net.bytebuddy.agent.ByteBuddyAgent
import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.ClassWriter
import net.bytebuddy.jar.asm.Label
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.lang.instrument.ClassFileTransformer
import java.net.URLClassLoader
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Keeps weaving from pushing a method past HotSpot's limits. HotSpot never compiles a method whose
 * code is over [HUGE_METHOD_LIMIT] bytes (`DontCompileHugeMethods` is on by default), so a method
 * that crosses it because of probes runs interpreted for the life of the process. The class file
 * format caps a method at 65535 bytes, and weaving a method past it fails the class with
 * `MethodTooLargeException`.
 *
 * [SizeGuard] keeps weaving under both limits by bounding each method's woven length and leaving a
 * method that would cross one with its entry probe only. The sweep still checks, as a net, that no
 * corpus method crosses 8000, and that the bound is never below the woven length.
 *
 * The sweep weaves every class of every benchmark corpus through the real transformer. It also
 * reports, without asserting, how many methods weaving pushes past `FreqInlineSize` (325 bytes),
 * above which a hot method is no longer inlined, and the largest woven method per corpus. The
 * report goes to the file named by the `otherlode.codesize.report` system property.
 *
 * So the sweep cannot pass by weaving nothing, at least [MIN_WOVEN_OF_CORPUS] of each corpus's
 * classes must weave, and at least [MIN_WOVEN_SHARE] of the classes the transformer woven or failed
 * on. A class the agent refuses because its own superclass or interface is not on its class set's
 * classpath, such as spring-webmvc's JSP tags, is left out of that share when it also fails to load
 * in a fresh loader for want of a class, since no JVM with that classpath could define it. A type
 * absent from a field or method signature does not excuse a class: a decoration describes nothing
 * the class names, so the class weaves. At most [MAX_EXCUSED_SHARE] of a corpus may be left out, so a wrong classpath cannot excuse everything.
 */
class CodeSizeLimitsTest {
    private val resource = ResourceAttributes("test", null, "instance-1", null, "run-1")

    /** One method's code length in the class file as written and as woven. */
    private data class MethodSize(
        val className: String,
        val method: String,
        val original: Int,
        val woven: Int,
        val bound: Int = woven,
        val probed: Boolean = false,
    ) {
        val crossesHugeLimit get() = original <= HUGE_METHOD_LIMIT && woven > HUGE_METHOD_LIMIT
        val crossesInlineLimit get() = original <= FREQ_INLINE_SIZE && woven > FREQ_INLINE_SIZE
    }

    /** What weaving one corpus produced. */
    private class SweepResult(
        val corpus: String,
        val classes: Int,
        val accepted: Int,
        val woven: Int,
        val failures: Map<String, String>,
        val sizes: List<MethodSize>,
        val unresolvable: Map<String, String>,
        val loadable: List<String>,
        val missing: List<String>,
    ) {
        val pastHugeLimit get() = sizes.filter { it.crossesHugeLimit }
        val pastInlineLimit get() = sizes.count { it.crossesInlineLimit }
        val tooLarge get() = failures.filterValues(::isTooLarge)
        val largest get() = sizes.maxByOrNull { it.woven }
        val belowActual get() = sizes.filter { it.probed && it.bound < it.woven }
        val roomestBound get() = sizes.filter { it.probed }.maxByOrNull { it.bound - it.woven }
        val tightestBound get() = sizes.filter { it.probed }.minByOrNull { it.bound - it.woven }
    }

    private fun sweep(corpusName: String): SweepResult {
        val corpus = BenchmarkCorpus.load(corpusName)
        val sizes = mutableListOf<MethodSize>()
        val failures = sortedMapOf<String, String>()
        val unresolvable = sortedMapOf<String, String>()
        val missing = mutableListOf<String>()
        val loadable = mutableListOf<String>()
        var accepted = 0
        var woven = 0
        for (set in corpus.classSets) {
            val urls = CorpusWeaving.classSetUrls(corpusName, set)
            URLClassLoader(urls.toTypedArray(), ClassLoader.getPlatformClassLoader()).use { loader ->
                val registry = ProbeRegistry()
                val transformer = HotPathWeaver.offlineTransformer(corpus.includePackages, registry)
                val thrown = mutableMapOf<String, String>()
                for ((internalName, bytes) in set.classes) {
                    val result =
                        try {
                            transformer.transform(loader, internalName, null, null, bytes)
                        } catch (t: Throwable) {
                            thrown[internalName.replace('/', '.')] = generateSequence(t) { it.cause }.joinToString(" <- ")
                            continue
                        }
                    if (result != null) {
                        woven++
                        accepted++
                        val before = codeLengths(bytes)
                        val after = codeLengths(result)
                        // A method with probes always grows, so the methods that grew are the ones the agent probes.
                        val grown = before.filter { (method, length) -> (after[method] ?: length) > length }.keys
                        val analysis =
                            BranchSiteAnalyzer.analyze(
                                bytes,
                                { name -> loader.getResourceAsStream("$name.class")?.use { it.readBytes() } },
                                corpus.includePackages,
                            ) { name, descriptor -> (name + descriptor) in grown }
                        // A `$default` method and a type initializer grow by probes of their own, which the branch guard does not bound.
                        val probed =
                            grown.filterTo(mutableSetOf()) { method ->
                                method != "<clinit>()V" &&
                                    analysis.defaultSites.none { it.defaultName + it.defaultDescriptor == method }
                            }
                        val bounds = analysis.sizeGuard.bounds
                        for ((method, length) in before) {
                            val wovenLength = after[method]
                            if (wovenLength ==
                                null
                            ) {
                                missing += "$internalName.$method"
                            } else {
                                val found = bounds[method.substringBefore('(') to "(" + method.substringAfter('(')]
                                if (found != null && found.original != length) {
                                    missing += "$internalName.$method: SizeGuard read $length bytes as ${found.original}"
                                }
                                val bound =
                                    if (method in probed) found?.bound ?: (length + SizeGuard.ENTRY_PROBE) else wovenLength
                                sizes += MethodSize(internalName, method, length, wovenLength, bound, method in probed)
                            }
                        }
                    }
                }
                // The transformer both records a skip and throws for a class it cannot weave, so a class is keyed once by its dotted name.
                val failed = sortedMapOf<String, String>()
                failed.putAll(thrown)
                for (s in registry.manifest(resource).skippedClasses) failed[s.className] = s.reason.replace('\n', ' ')
                accepted += failed.size
                for ((name, reason) in failed) {
                    failures["${set.name}:$name"] = reason
                    if (namesAbsentType(reason, loader)) {
                        if (failsToLoad(name, urls)) unresolvable["${set.name}:$name"] = reason else loadable += "${set.name}:$name"
                    }
                }
            }
        }
        return SweepResult(corpusName, corpus.classCount, accepted, woven, failures, sizes, unresolvable, loadable, missing)
    }

    @Test
    fun `weaving the benchmark corpora never pushes a method past the compile limit`() {
        val started = System.nanoTime()
        val results = CorpusWeaving.CORPORA.map { sweep(it) }
        val lines = mutableListOf<String>()
        for (r in results) {
            val largest = r.largest
            lines += "${r.corpus}: classes=${r.classes} matched=${r.accepted} woven=${r.woven} failed=${r.failures.size} " +
                "methods=${r.sizes.size} pastHugeLimit=${r.pastHugeLimit.size} pastFreqInlineSize=${r.pastInlineLimit} " +
                "absentSupertype=${r.unresolvable.size} tooLarge=${r.tooLarge.size} largestWoven=${largest?.let {
                    "${it.className}.${it.method} ${it.woven} bytes (${it.original} before)"
                }}"
            r.roomestBound?.let { lines += "    bound minus woven: most ${it.bound - it.woven} (${it.className}.${it.method})" }
            r.tightestBound?.let { lines += "    bound minus woven: least ${it.bound - it.woven} (${it.className}.${it.method})" }
            val reasons =
                r.failures.values
                    .groupingBy { it.substringAfter("cannot be defined: ").take(REASON_WIDTH) }
                    .eachCount()
                    .entries
                    .sortedByDescending { it.value }
            for ((reason, count) in reasons) lines += "    failed x$count: $reason"
            for (name in r.loadable) lines += "    loads, but was refused for an absent supertype: $name"
        }
        val report = lines.joinToString("\n", postfix = "\n")
        System.getProperty(REPORT_PROPERTY)?.let { path ->
            File(path).also { it.parentFile.mkdirs() }.writeText(report)
        }
        println(
            "code-size sweep: " + results.joinToString("; ") { "${it.corpus} ${it.pastInlineLimit} past 325" } +
                " (${(System.nanoTime() - started) / NANOS_PER_MILLI} ms), report in ${System.getProperty(REPORT_PROPERTY)}",
        )

        for (r in results) {
            assertTrue(r.pastHugeLimit.isEmpty(), "${r.corpus}: weaving pushed methods past $HUGE_METHOD_LIMIT bytes: ${r.pastHugeLimit}")
            assertTrue(r.tooLarge.isEmpty(), "${r.corpus}: classes skipped as too large: ${r.tooLarge}")
            assertTrue(r.belowActual.isEmpty(), "${r.corpus}: the size bound is below the woven length for ${r.belowActual}")
            assertTrue(r.missing.isEmpty(), "${r.corpus}: methods absent from the woven class: ${r.missing}")
            assertTrue(
                r.unresolvable.size <= r.accepted * MAX_EXCUSED_SHARE,
                "${r.corpus}: ${r.unresolvable.size} of ${r.accepted} classes excused as missing a supertype; is the classpath wrong?",
            )
            assertTrue(
                r.woven >= r.classes * MIN_WOVEN_OF_CORPUS,
                "${r.corpus}: only ${r.woven} of ${r.classes} classes wove; did the type matcher stop accepting them?",
            )
            val weavable = r.accepted - r.unresolvable.size
            assertTrue(
                r.woven >= weavable * MIN_WOVEN_SHARE,
                "${r.corpus}: only ${r.woven} of $weavable weavable classes wove; failures: ${r.failures.entries.take(FAILURES_SHOWN)}",
            )
        }
    }

    /**
     * Whether [reason] is the agent refusing a class for a supertype that [loader] cannot see either:
     * an optional dependency the class set does not carry, so the class could not be defined in any
     * JVM with this classpath. A supertype the loader does provide is not excused, since refusing
     * the class for it would be the agent's own fault.
     */
    private fun namesAbsentType(
        reason: String,
        loader: ClassLoader,
    ): Boolean {
        val typeName = UNRESOLVED_TYPE.find(reason)?.groupValues?.get(1) ?: return false
        return loader.getResource(typeName.replace('.', '/') + ".class") == null
    }

    /**
     * Whether [className] fails to load and initialise in a fresh loader over [urls]. A class the JVM
     * defines and runs without the agent is the agent's loss, not an excused one.
     */
    private fun failsToLoad(
        className: String,
        urls: List<java.net.URL>,
    ): Boolean =
        URLClassLoader(urls.toTypedArray(), ClassLoader.getPlatformClassLoader()).use { fresh ->
            try {
                Class.forName(className, true, fresh)
                false
            } catch (e: NoClassDefFoundError) {
                true
            } catch (e: ExceptionInInitializerError) {
                e.cause is NoClassDefFoundError
            }
        }

    /** One synthetic class woven through the real transformer, with what the weave logged. */
    private class Woven(
        val name: String,
        val original: ByteArray,
        val woven: ByteArray,
        val registry: ProbeRegistry,
        val warnings: List<String>,
        /** The loader the transformer was given, which the registry keys the class's array by. */
        val loader: ClassLoader,
    ) {
        val originalLength get() = checkNotNull(codeLengths(original)["big(I)I"])
        val wovenLength get() = checkNotNull(codeLengths(woven)["big(I)I"])
        val sites get() =
            registry.manifest(ResourceAttributes("test", null, "instance-1", null, "run-1")).probes.count {
                it.kind ==
                    ProbeKind.BRANCH
            }
    }

    private fun ProbeRegistry.manifestProbes() = manifest(ResourceAttributes("test", null, "instance-1", null, "run-1")).probes

    private fun weave(
        name: String,
        blocks: Int,
    ): Woven = weave(name, hugeClass(name, blocks))

    /**
     * Weaves [original] through the real transformer. [receivedOf] gives the bytes the transformer
     * is handed when an earlier transformer has changed them: the agent's byte capture sees those,
     * and the class file stays what the loader serves.
     */
    private fun weave(
        name: String,
        original: ByteArray,
        receivedOf: ((ByteArray) -> ByteArray)? = null,
    ): Woven {
        val registry = ProbeRegistry()
        val transformers = mutableListOf<ClassFileTransformer>()
        val config = AgentConfig.parse("includePackages=com.example.huge")
        OtherlodeInstrumentation(config, registry).install(HotPathWeaver.capturing(ByteBuddyAgent.install(), transformers))
        val classes = mutableMapOf(name to original)
        val loader = InMemoryLoader(classes)
        val received = receivedOf?.invoke(original) ?: original
        val warnings = mutableListOf<String>()
        val woven =
            captureWarnings {
                transformers.fold(received) { bytes, transformer ->
                    transformer.transform(loader, name, null, null, bytes) ?: bytes
                }
            }.let { (bytes, logged) ->
                warnings += logged
                checkNotNull(bytes) { "the class was not woven: ${registry.manifest(resource).skippedClasses}" }
            }
        check(!woven.contentEquals(received)) { "the transformer left $name unchanged: ${registry.manifest(resource).skippedClasses}" }
        // The registry keys the class's array by this loader, so it must be the one that defines the woven class.
        classes[name] = woven
        return Woven(name, original, woven, registry, warnings, loader)
    }

    private fun captureWarnings(block: () -> ByteArray?): Pair<ByteArray?, List<String>> {
        val warnings = mutableListOf<String>()
        val handler =
            object : Handler() {
                override fun publish(record: LogRecord) {
                    if (record.level == Level.WARNING) warnings += record.message
                }

                override fun flush() {}

                override fun close() {}
            }
        val logger = Logger.getLogger("dev.otherlode.instrumentation.OtherlodeInstrumentation")
        val level = logger.level
        logger.addHandler(handler)
        logger.level = Level.ALL
        try {
            return block() to warnings
        } finally {
            logger.removeHandler(handler)
            logger.level = level
        }
    }

    /** Runs `big` of the woven class in the loader the transformer saw, for each argument. */
    private fun runWoven(
        w: Woven,
        arguments: IntRange,
    ): List<Int> = call(w.loader.loadClass(w.name.replace('/', '.')), arguments)

    /** Runs `big` of the unwoven class in a loader of its own, for each argument. */
    private fun runOriginal(
        w: Woven,
        arguments: IntRange,
    ): List<Int> = call(InMemoryLoader(mapOf(w.name to w.original)).loadClass(w.name.replace('/', '.')), arguments)

    private fun call(
        type: Class<*>,
        arguments: IntRange,
    ): List<Int> {
        val method = type.getMethod("big", Int::class.javaPrimitiveType)
        return arguments.map { method.invoke(null, it) as Int }
    }

    private fun entryHits(w: Woven): Long {
        val entry = w.registry.manifestProbes().single { it.kind == ProbeKind.METHOD && it.methodName == "big" }
        return w.registry
            .computeDeltaBatch(resource)
            .batch.deltas
            .filter { it.probeIndex == entry.probeIndex }
            .sumOf { it.hitsTotal }
    }

    @Test
    fun `a method whose probes would cross the compile limit is woven with its entry probe only`() {
        val w = weave("com/example/huge/Big", HUGE_BLOCKS)
        assertTrue(w.originalLength <= HUGE_METHOD_LIMIT, "the synthetic method starts at ${w.originalLength} bytes")
        assertTrue(
            w.originalLength + HUGE_BLOCKS * SizeGuard.TWO_WAY_SITE > HUGE_METHOD_LIMIT,
            "with its branch probes the synthetic method would stay under the limit",
        )
        assertTrue(w.wovenLength <= HUGE_METHOD_LIMIT, "the synthetic method weaves to ${w.wovenLength} bytes")
        assertTrue(w.wovenLength <= w.originalLength + SizeGuard.ENTRY_PROBE, "more than the entry probe was added: ${w.wovenLength}")
        val warning = w.warnings.single { "Big#big(I)I" in it }
        assertTrue("would not be JIT-compiled" in warning, warning)
        val results = runWoven(w, 195..215)
        assertEquals(runOriginal(w, 195..215), results, "the woven method computes something else")
        assertEquals(results.size.toLong(), entryHits(w), "the entry probe miscounts")
        assertEquals(0, w.sites, "branch probes were woven")
    }

    @Test
    fun `a branch-dense method that would cross the class file limit is woven with its entry probe only`() {
        val w = weave("com/example/huge/TooLarge", TOO_LARGE_BLOCKS)
        assertTrue(w.originalLength > HUGE_METHOD_LIMIT, "the synthetic method starts at ${w.originalLength} bytes")
        assertTrue(w.originalLength + TOO_LARGE_BLOCKS * SizeGuard.TWO_WAY_SITE > MAX_CODE_LENGTH, "the bound would not cross the limit")
        assertTrue(
            w.registry
                .manifest(resource)
                .skippedClasses
                .isEmpty(),
            "the class was skipped: ${w.registry.manifest(resource).skippedClasses}",
        )
        assertTrue(w.wovenLength <= w.originalLength + SizeGuard.ENTRY_PROBE, "more than the entry probe was added: ${w.wovenLength}")
        val warning = w.warnings.single { "TooLarge#big(I)I" in it }
        assertTrue("would not fit a class file" in warning, warning)
        val arguments = 195..215
        val results = runWoven(w, arguments)
        assertEquals(runOriginal(w, arguments), results, "the woven method computes something else")
        assertEquals(results.size.toLong(), entryHits(w), "the entry probe miscounts")
        assertEquals(0, w.sites, "branch probes were woven")
    }

    @Test
    fun `a method under both limits keeps its branch probes and logs nothing`() {
        val w = weave("com/example/huge/Small", SMALL_BLOCKS)
        assertEquals(runOriginal(w, 195..215), runWoven(w, 195..215))
        assertEquals(SMALL_BLOCKS * 2, w.sites)
        assertTrue(w.warnings.none { "Small#big" in it }, "${w.warnings}")
        assertTrue(w.wovenLength <= w.originalLength + SizeGuard.ENTRY_PROBE + SMALL_BLOCKS * SizeGuard.TWO_WAY_SITE)
    }

    @Test
    fun `a method above the compile limit whose bound fits a class file keeps its branch probes and logs nothing`() {
        val w = weave("com/example/huge/Large", LARGE_BLOCKS)
        assertTrue(w.originalLength > HUGE_METHOD_LIMIT, "the synthetic method starts at ${w.originalLength} bytes")
        assertTrue(w.originalLength + SizeGuard.ENTRY_PROBE + LARGE_BLOCKS * SizeGuard.TWO_WAY_SITE <= MAX_CODE_LENGTH)
        assertEquals(LARGE_BLOCKS * 2, w.sites)
        assertTrue(w.warnings.none { "Large#big" in it }, "${w.warnings}")
        assertEquals(runOriginal(w, 195..215), runWoven(w, 195..215))
    }

    @Test
    fun `a method its entry probe alone carries past the compile limit keeps its branch probes and logs that`() {
        val w = weave("com/example/huge/Edge", EDGE_BLOCKS)
        assertTrue(w.originalLength <= HUGE_METHOD_LIMIT, "the synthetic method starts at ${w.originalLength} bytes")
        assertTrue(w.originalLength + SizeGuard.ENTRY_PROBE > HUGE_METHOD_LIMIT)
        assertEquals(EDGE_BLOCKS * 2, w.sites, "dropping would not have brought the method under the limit")
        val warning = w.warnings.single { "Edge#big(I)I" in it }
        assertTrue("its entry probe can take it to" in warning && "may not compile it" in warning, warning)
        assertEquals(runOriginal(w, 195..215), runWoven(w, 195..215))
    }

    @Test
    fun `a method with no branch site that its entry probe takes past the compile limit logs that`() {
        val name = "com/example/huge/Quiet"
        val original = nopBigClass(name, QUIET_LENGTH)
        val w = weave(name, original)
        assertEquals(QUIET_LENGTH, w.originalLength)
        assertTrue(w.originalLength + SizeGuard.ENTRY_PROBE > HUGE_METHOD_LIMIT)
        val warning = w.warnings.single { "Quiet#big(I)I" in it }
        assertTrue("its entry probe can take it to" in warning, warning)
        assertEquals(runOriginal(w, 1..3), runWoven(w, 1..3))
        val small = weave("com/example/huge/Short", nopBigClass("com/example/huge/Short", QUIET_LENGTH - SizeGuard.ENTRY_PROBE))
        assertTrue(small.warnings.none { "Short#big" in it }, "${small.warnings}")
    }

    @Test
    fun `a guarded method whose branches do not pair with the received bytes is not warned about`() {
        val name = "com/example/huge/Unpaired"
        val w = weave(name, hugeClass(name, HUGE_BLOCKS)) { withLeadingBranch(it) }
        assertEquals(0, w.sites)
        assertTrue(w.warnings.none { "Unpaired#big" in it }, "${w.warnings}")
    }

    @Test
    fun `a method that an earlier transformer grows past the class file limit is woven with its entry probe only`() {
        val name = "com/example/huge/Grown"
        val w = weave(name, hugeClass(name, SMALL_BLOCKS)) { padded(it, GROWN_PADDING) }
        val receivedLength = checkNotNull(codeLengths(padded(w.original, GROWN_PADDING))["big(I)I"])
        assertTrue(w.originalLength + SizeGuard.ENTRY_PROBE + SMALL_BLOCKS * SizeGuard.TWO_WAY_SITE <= HUGE_METHOD_LIMIT)
        assertTrue(receivedLength + SizeGuard.ENTRY_PROBE <= MAX_CODE_LENGTH, "the entry probe alone must fit")
        assertTrue(
            receivedLength + SizeGuard.ENTRY_PROBE + SMALL_BLOCKS * SizeGuard.TWO_WAY_SITE > MAX_CODE_LENGTH,
            "the received bytes cross the class file limit with their branch probes",
        )
        assertEquals(0, w.sites, "branch probes were woven into bytes the class file limit rules out")
        assertTrue(
            w.registry
                .manifest(resource)
                .skippedClasses
                .isEmpty(),
            "${w.registry.manifest(resource).skippedClasses}",
        )
        assertTrue(w.wovenLength <= receivedLength + SizeGuard.ENTRY_PROBE, "more than the entry probe was added: ${w.wovenLength}")
        val warning = w.warnings.single { "Grown#big(I)I" in it }
        assertTrue("would not fit a class file" in warning, warning)
        assertEquals(runOriginal(w, 195..215), runWoven(w, 195..215))
        assertEquals(21L, entryHits(w))
    }

    @Test
    fun `an earlier transformer's growth past the compile limit leaves the branch probes in`() {
        val name = "com/example/huge/Padded"
        val w = weave(name, hugeClass(name, SMALL_BLOCKS)) { padded(it, COMPILE_PADDING) }
        assertEquals(SMALL_BLOCKS * 2, w.sites)
        assertTrue(w.warnings.none { "Padded#big" in it }, "${w.warnings}")
    }

    private class InMemoryLoader(
        private val classes: Map<String, ByteArray>,
    ) : ClassLoader(CodeSizeLimitsTest::class.java.classLoader) {
        override fun findClass(name: String): Class<*> {
            val bytes = classes[name.replace('.', '/')] ?: throw ClassNotFoundException(name)
            return defineClass(name, bytes, 0, bytes.size)
        }

        override fun getResourceAsStream(name: String): InputStream? =
            classes[name.removeSuffix(".class")]?.let { ByteArrayInputStream(it) } ?: super.getResourceAsStream(name)
    }

    private companion object {
        const val HUGE_METHOD_LIMIT = 8000
        const val FREQ_INLINE_SIZE = 325
        const val MIN_WOVEN_SHARE = 0.9
        const val MIN_WOVEN_OF_CORPUS = 0.75
        const val MAX_EXCUSED_SHARE = 0.2
        const val TOO_LARGE_BLOCKS = 2000
        val UNRESOLVED_TYPE = Regex("cannot be defined: its supertype (\\S+) could not be read from its loader")

        /** Whether [reason] is ASM's message for a method or class the class file format cannot hold, as the skip records it. */
        fun isTooLarge(reason: String): Boolean = "Method too large: " in reason || "Class too large: " in reason

        const val REPORT_PROPERTY = "otherlode.codesize.report"
        const val REASON_WIDTH = 120
        const val FAILURES_SHOWN = 5
        const val NANOS_PER_MILLI = 1_000_000
        const val HUGE_BLOCKS = 500
        const val SMALL_BLOCKS = 100
        const val LARGE_BLOCKS = 850
        const val EDGE_BLOCKS = 799
        const val QUIET_LENGTH = 7990
        const val GROWN_PADDING = 62000

        /** Grows the received method to 7504 bytes: under 8000 itself, while its bound with probes crosses 8000. */
        const val COMPILE_PADDING = 6500
        const val MAX_CODE_LENGTH = 65535

        /** A class with `static int big(int x)` holding [blocks] blocks of `if (x == k) y++`, ten bytes each. */
        fun hugeClass(
            internalName: String,
            blocks: Int,
        ): ByteArray {
            val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
            cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null)
            cw.visitSource("Big.java", null)
            val ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
            ctor.visitCode()
            ctor.visitVarInsn(Opcodes.ALOAD, 0)
            ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            ctor.visitInsn(Opcodes.RETURN)
            ctor.visitMaxs(0, 0)
            ctor.visitEnd()
            val mv = cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "big", "(I)I", null, null)
            mv.visitCode()
            val start = Label()
            mv.visitLabel(start)
            mv.visitLineNumber(1, start)
            mv.visitInsn(Opcodes.ICONST_0)
            mv.visitVarInsn(Opcodes.ISTORE, 1)
            for (i in 0 until blocks) {
                val skip = Label()
                mv.visitVarInsn(Opcodes.ILOAD, 0)
                mv.visitIntInsn(Opcodes.SIPUSH, 200 + i)
                mv.visitJumpInsn(Opcodes.IF_ICMPNE, skip)
                mv.visitIincInsn(1, 1)
                mv.visitLabel(skip)
            }
            mv.visitVarInsn(Opcodes.ILOAD, 1)
            mv.visitInsn(Opcodes.IRETURN)
            mv.visitMaxs(0, 0)
            mv.visitEnd()
            cw.visitEnd()
            return cw.toByteArray()
        }

        /** A class whose `static int big(int)` is [length] bytes of code: `nop`s, `iload_0` and `ireturn`. */
        fun nopBigClass(
            internalName: String,
            length: Int,
        ): ByteArray {
            val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
            cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null)
            cw.visitSource("Big.java", null)
            val mv = cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "big", "(I)I", null, null)
            mv.visitCode()
            repeat(length - 2) { mv.visitInsn(Opcodes.NOP) }
            mv.visitVarInsn(Opcodes.ILOAD, 0)
            mv.visitInsn(Opcodes.IRETURN)
            mv.visitMaxs(0, 0)
            mv.visitEnd()
            cw.visitEnd()
            return cw.toByteArray()
        }

        /** [bytes] with [nops] `nop`s at the start of `big`, as a transformer ahead of this agent might leave. */
        fun padded(
            bytes: ByteArray,
            nops: Int,
        ): ByteArray = rewriteBig(bytes) { repeat(nops) { visitInsn(Opcodes.NOP) } }

        /** [bytes] with a conditional jump at the start of `big` that goes nowhere, so its branches no longer pair. */
        fun withLeadingBranch(bytes: ByteArray): ByteArray =
            rewriteBig(bytes) {
                val next = Label()
                visitVarInsn(Opcodes.ILOAD, 0)
                visitJumpInsn(Opcodes.IFEQ, next)
                visitLabel(next)
                visitFrame(Opcodes.F_SAME, 0, null, 0, null)
            }

        private fun rewriteBig(
            bytes: ByteArray,
            atStart: MethodVisitor.() -> Unit,
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
                    ): MethodVisitor {
                        val delegate = super.visitMethod(access, name, descriptor, signature, exceptions)
                        if (name != "big") return delegate
                        return object : MethodVisitor(Opcodes.ASM9, delegate) {
                            override fun visitCode() {
                                super.visitCode()
                                atStart()
                            }
                        }
                    }
                },
                0,
            )
            return writer.toByteArray()
        }

        /** The `Code` attribute length of each method, keyed `name + descriptor`, read straight from the class file. */
        fun codeLengths(bytes: ByteArray): Map<String, Int> {
            val d = DataInputStream(ByteArrayInputStream(bytes))
            d.readInt()
            d.readUnsignedShort()
            d.readUnsignedShort()
            val cpCount = d.readUnsignedShort()
            val utf8 = arrayOfNulls<String>(cpCount)
            var i = 1
            while (i < cpCount) {
                when (val tag = d.readUnsignedByte()) {
                    1 -> {
                        utf8[i] = d.readUTF()
                    }

                    3, 4 -> {
                        d.skipNBytes(4)
                    }

                    5, 6 -> {
                        d.skipNBytes(8)
                        i++
                    }

                    7, 8, 16, 19, 20 -> {
                        d.skipNBytes(2)
                    }

                    9, 10, 11, 12, 17, 18 -> {
                        d.skipNBytes(4)
                    }

                    15 -> {
                        d.skipNBytes(3)
                    }

                    else -> {
                        error("unknown constant pool tag $tag")
                    }
                }
                i++
            }
            d.skipNBytes(6)
            d.skipNBytes(2L * d.readUnsignedShort())
            repeat(d.readUnsignedShort()) {
                d.skipNBytes(6)
                repeat(d.readUnsignedShort()) {
                    d.skipNBytes(2)
                    d.skipNBytes(d.readInt().toLong())
                }
            }
            val result = linkedMapOf<String, Int>()
            repeat(d.readUnsignedShort()) {
                d.readUnsignedShort()
                val name = utf8[d.readUnsignedShort()]!!
                val descriptor = utf8[d.readUnsignedShort()]!!
                repeat(d.readUnsignedShort()) {
                    val attribute = utf8[d.readUnsignedShort()]
                    val length = d.readInt()
                    if (attribute == "Code") {
                        d.skipNBytes(4)
                        result[name + descriptor] = d.readInt()
                        d.skipNBytes(length - 8L)
                    } else {
                        d.skipNBytes(length.toLong())
                    }
                }
            }
            return result
        }
    }
}
