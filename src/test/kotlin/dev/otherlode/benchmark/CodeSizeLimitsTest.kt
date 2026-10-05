package dev.otherlode.benchmark

import dev.otherlode.export.ResourceAttributes
import dev.otherlode.registry.ProbeRegistry
import net.bytebuddy.jar.asm.ClassWriter
import net.bytebuddy.jar.asm.Label
import net.bytebuddy.jar.asm.Opcodes
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.net.URLClassLoader
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Keeps weaving from pushing a method past HotSpot's limits. HotSpot never compiles a method whose
 * code is over [HUGE_METHOD_LIMIT] bytes (`DontCompileHugeMethods` is on by default), so a method
 * that crosses it because of probes runs interpreted for the life of the process. The class file
 * format caps a method at 65535 bytes, and weaving a method past it fails the class with
 * `MethodTooLargeException`.
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
 * absent from a field or method signature does not excuse a class: the agent describes it as a
 * placeholder and weaves the class. At most
 * [MAX_EXCUSED_SHARE] of a corpus may be left out, so a wrong classpath cannot excuse everything.
 */
class CodeSizeLimitsTest {
    private val resource = ResourceAttributes("test", null, "instance-1", null, "run-1")

    /** One method's code length in the class file as written and as woven. */
    private data class MethodSize(
        val className: String,
        val method: String,
        val original: Int,
        val woven: Int,
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
            val classpath = System.getProperty("$CLASSPATH_PROPERTY$corpusName.${set.name}")
            check(!classpath.isNullOrBlank()) { "no classpath for $corpusName.${set.name}" }
            val corpusPath = checkNotNull(System.getProperty("${BenchmarkCorpus.PROPERTY_PREFIX}$corpusName.${set.name}"))
            val urls =
                (corpusPath.split(File.pathSeparator) + classpath.split(File.pathSeparator))
                    .filter { it.isNotEmpty() }
                    .map { File(it).toURI().toURL() }
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
                        for ((method, length) in before) {
                            val wovenLength = after[method]
                            if (wovenLength ==
                                null
                            ) {
                                missing += "$internalName.$method"
                            } else {
                                sizes += MethodSize(internalName, method, length, wovenLength)
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
        val results = CORPORA.map { sweep(it) }
        val lines = mutableListOf<String>()
        for (r in results) {
            val largest = r.largest
            lines += "${r.corpus}: classes=${r.classes} matched=${r.accepted} woven=${r.woven} failed=${r.failures.size} " +
                "methods=${r.sizes.size} pastHugeLimit=${r.pastHugeLimit.size} pastFreqInlineSize=${r.pastInlineLimit} " +
                "absentSupertype=${r.unresolvable.size} tooLarge=${r.tooLarge.size} largestWoven=${largest?.let {
                    "${it.className}.${it.method} ${it.woven} bytes (${it.original} before)"
                }}"
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

    @Test
    fun `the check reports a class that weaving pushes past the class file limit`() {
        val name = "com/example/huge/TooLarge"
        val original = hugeClass(name, TOO_LARGE_BLOCKS)
        val registry = ProbeRegistry()
        val transformer = HotPathWeaver.offlineTransformer(listOf("com.example.huge"), registry)
        val woven = runCatching { transformer.transform(InMemoryLoader(mapOf(name to original)), name, null, null, original) }
        assertTrue(woven.getOrNull() == null, "a class too large to write was woven anyway")
        val reasons = registry.manifest(resource).skippedClasses.map { it.reason }
        assertTrue(reasons.any(::isTooLarge), "no skip reason reads as too large: $reasons")
    }

    @Test
    fun `the check reports a method that weaving pushes past the limit`() {
        val name = "com/example/huge/Big"
        val original = hugeClass(name, HUGE_BLOCKS)
        val registry = ProbeRegistry()
        val transformer = HotPathWeaver.offlineTransformer(listOf("com.example.huge"), registry)
        val woven = checkNotNull(transformer.transform(InMemoryLoader(mapOf(name to original)), name, null, null, original))
        val size = MethodSize(name, "big(I)I", checkNotNull(codeLengths(original)["big(I)I"]), checkNotNull(codeLengths(woven)["big(I)I"]))
        assertTrue(size.original <= HUGE_METHOD_LIMIT, "the synthetic method starts at ${size.original} bytes")
        assertTrue(size.crossesHugeLimit, "the synthetic method weaves to ${size.woven} bytes")
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

        const val CLASSPATH_PROPERTY = "otherlode.codesize.classpath."
        const val REPORT_PROPERTY = "otherlode.codesize.report"
        const val REASON_WIDTH = 120
        const val FAILURES_SHOWN = 5
        const val NANOS_PER_MILLI = 1_000_000
        const val HUGE_BLOCKS = 500
        val CORPORA = listOf("demo", "demo-spring", "scala", "spring-webmvc", "ktor-server-core")

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
