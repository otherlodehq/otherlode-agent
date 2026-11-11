package dev.otherlode.benchmark

import dev.otherlode.export.ProbeKind
import dev.otherlode.export.ResourceAttributes
import dev.otherlode.instrumentation.FrameRefusingClassWriter
import dev.otherlode.registry.ProbeRegistry
import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.ClassWriter
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.lang.reflect.Modifier
import java.net.URI
import javax.tools.FileObject
import javax.tools.ForwardingJavaFileManager
import javax.tools.JavaFileManager
import javax.tools.JavaFileObject
import javax.tools.SimpleJavaFileObject
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FramesShapesTest {
    private val resource = ResourceAttributes("test", null, "instance-1", null, "run-1")

    private class FixtureLoader(
        parent: ClassLoader,
        private val resources: Map<String, ByteArray>,
        val definitions: MutableMap<String, ByteArray>,
    ) : ClassLoader(parent) {
        override fun findClass(name: String): Class<*> {
            val bytes = definitions[name.replace('.', '/')] ?: throw ClassNotFoundException(name)
            return defineClass(name, bytes, 0, bytes.size)
        }

        override fun getResourceAsStream(name: String): InputStream? =
            resources[name.removeSuffix(".class")]?.let { ByteArrayInputStream(it) } ?: super.getResourceAsStream(name)
    }

    private class InMemorySource(
        name: String,
        private val code: String,
    ) : SimpleJavaFileObject(URI.create("string:///${name.replace('.', '/')}.java"), JavaFileObject.Kind.SOURCE) {
        override fun getCharContent(ignoreEncodingErrors: Boolean): CharSequence = code
    }

    private class InMemoryClass(
        name: String,
    ) : SimpleJavaFileObject(URI.create("mem:///${name.replace('.', '/')}.class"), JavaFileObject.Kind.CLASS) {
        val bytes = ByteArrayOutputStream()

        override fun openOutputStream() = bytes
    }

    private fun compile(
        name: String,
        source: String,
        release: Int,
    ): Map<String, ByteArray> {
        val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler()) { "the tests need a JDK, not a JRE" }
        val outputs = LinkedHashMap<String, InMemoryClass>()
        val manager =
            object : ForwardingJavaFileManager<JavaFileManager>(compiler.getStandardFileManager(null, null, null)) {
                override fun getJavaFileForOutput(
                    location: JavaFileManager.Location,
                    className: String,
                    kind: JavaFileObject.Kind,
                    sibling: FileObject?,
                ): JavaFileObject = InMemoryClass(className).also { outputs[className.replace('.', '/')] = it }
            }
        val ok =
            compiler
                .getTask(
                    null,
                    manager,
                    null,
                    listOf("--release", release.toString(), "-nowarn", "-Xlint:none"),
                    null,
                    listOf(InMemorySource(name, source)),
                ).call()
        check(ok) { "javac failed on $name at release $release" }
        return outputs.mapValues { it.value.bytes.toByteArray() }
    }

    private fun atVersion50(
        bytes: Map<String, ByteArray>,
        keepFrames: Boolean,
    ): Map<String, ByteArray> =
        bytes.mapValues { (_, original) ->
            val writer = ClassWriter(0)
            ClassReader(original).accept(
                object : ClassVisitor(Opcodes.ASM9, writer) {
                    override fun visit(
                        version: Int,
                        access: Int,
                        name: String,
                        signature: String?,
                        superName: String?,
                        interfaces: Array<out String>?,
                    ) = super.visit(Opcodes.V1_6, access, name, signature, superName, interfaces)
                },
                if (keepFrames) 0 else ClassReader.SKIP_FRAMES,
            )
            writer.toByteArray()
        }

    private fun shapesSource(
        simpleName: String,
        withLambda: Boolean,
    ): String {
        val template =
            checkNotNull(javaClass.getResourceAsStream("/frames/Shapes.java.txt")) {
                "the Shapes source is missing"
            }.use { String(it.readBytes()) }
        val kept = if (withLambda) template else template.replace(Regex("// LAMBDA\\n[^\\n]*\\n"), "")
        return kept.replace("__CLASS__", simpleName)
    }

    private fun bigSource(): String =
        buildString {
            appendLine("package $PACKAGE;")
            appendLine("public class Big {")
            appendLine("  public static int big(int n) {")
            appendLine("    int x = 1; int acc = 0;")
            appendLine("    for (int i = 0; i < n; i++) {")
            for (k in 0 until BIG_JUMPS) appendLine("      if (((i + $k) & 3) == 0) acc += ${k % 100}; else acc -= 1;")
            for (k in 0 until BIG_STRAIGHT_LINE) appendLine("      x = x * 31 + $k;")
            appendLine("    }")
            appendLine("    return acc + x;")
            appendLine("  }")
            appendLine("}")
        }

    private class Variant(
        val label: String,
        val classes: Map<String, ByteArray>,
        val shapes: String,
    )

    private fun variants(): List<Variant> {
        val available = Runtime.version().feature()
        val out = mutableListOf<Variant>()
        for (release in listOf(8, 11, 17, 21).filter { it <= available }) {
            out +=
                Variant(
                    "release $release",
                    compile("$PACKAGE.Shapes", shapesSource("Shapes", true), release) + compile("$PACKAGE.Big", bigSource(), release),
                    "Shapes",
                )
        }
        val plain = compile("$PACKAGE.Shapes50", shapesSource("Shapes50", false), 8) + compile("$PACKAGE.Big", bigSource(), 8)
        out += Variant("version 50 with frames", atVersion50(plain, keepFrames = true), "Shapes50")
        out += Variant("version 50 without frames", atVersion50(plain, keepFrames = false), "Shapes50")
        return out
    }

    private val calls: Map<String, List<Array<Any?>>> =
        mapOf(
            "doBreak" to listOf(arrayOf(3, "abc"), arrayOf(5, "x"), arrayOf(0, null)),
            "ternaryInNew" to listOf(arrayOf(true), arrayOf(false)),
            "longs" to
                listOf(
                    arrayOf(1L, 2.5, 1),
                    arrayOf(7L, 0.5, 5),
                    arrayOf(3L, 9.0, 10),
                    arrayOf(3L, 9.0, 1000),
                    arrayOf(3L, 9.0, -40),
                ),
            "handler" to listOf(arrayOf(""), arrayOf("12"), arrayOf("zz"), arrayOf("1000")),
            "ctor" to listOf(arrayOf(4), arrayOf(-7), arrayOf(0)),
            "stackAtJump" to listOf(arrayOf(true, 5), arrayOf(false, 0), arrayOf(true, 1)),
            "strSwitch" to listOf(arrayOf("a"), arrayOf("b"), arrayOf("Aa"), arrayOf("BB"), arrayOf("q")),
            "nested" to listOf(arrayOf(5, 5), arrayOf(2, 9), arrayOf(0, 0)),
            "mergeTypes" to listOf(arrayOf(1), arrayOf(-1), arrayOf(9)),
            "nullChecks" to listOf(arrayOf(null, null), arrayOf("a", "a"), arrayOf("a", null)),
            "lambda" to listOf(arrayOf(1), arrayOf(9)),
            "sync" to listOf(arrayOf(Any(), 3), arrayOf(Any(), -2)),
            "big" to listOf(arrayOf(0), arrayOf(7), arrayOf(40)),
        )

    private fun run(type: Class<*>): List<String> =
        type.declaredMethods
            .filter { Modifier.isStatic(it.modifiers) && Modifier.isPublic(it.modifiers) }
            .sortedBy { it.name }
            .flatMap { m ->
                calls.getValue(m.name).map { a ->
                    "${m.name}${a.toList()}=" + runCatching { m.invoke(null, *a) }.fold({ it.toString() }, { "threw ${it.cause}" })
                }
            }

    private fun frameCount(bytes: ByteArray): Int = frameCounts(bytes).first

    private fun frameCounts(bytes: ByteArray): Pair<Int, Int> {
        var frames = 0
        var mostInOneMethod = 0
        ClassReader(bytes).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor =
                    object : MethodVisitor(Opcodes.ASM9) {
                        private var inMethod = 0

                        override fun visitFrame(
                            type: Int,
                            numLocal: Int,
                            local: Array<out Any>?,
                            numStack: Int,
                            stack: Array<out Any>?,
                        ) {
                            frames++
                            mostInOneMethod = maxOf(mostInOneMethod, ++inMethod)
                        }
                    }
            },
            0,
        )
        return frames to mostInOneMethod
    }

    private fun longestCode(bytes: ByteArray): Int {
        var longest = 0
        val reader = ClassReader(bytes)
        val buffer = CharArray(reader.maxStringLength)
        var p = reader.header + 6
        p += 2 + 2 * reader.readUnsignedShort(p)
        repeat(2) { member ->
            var count = reader.readUnsignedShort(p)
            p += 2
            while (count-- > 0) {
                var attributes = reader.readUnsignedShort(p + 6)
                p += 8
                while (attributes-- > 0) {
                    val length = reader.readInt(p + 2)
                    if (member == 1 && reader.readUTF8(p, buffer) == "Code") longest = maxOf(longest, reader.readInt(p + 10))
                    p += 6 + length
                }
            }
        }
        return longest
    }

    private class Counts(
        private val hits: Map<Pair<String, ProbeKind>, Long>,
    ) {
        fun of(
            methodName: String,
            kind: ProbeKind,
        ): Long = hits[methodName to kind] ?: 0L
    }

    private fun countsOf(
        registry: ProbeRegistry,
        className: String,
    ): Counts {
        val probes = registry.manifest(resource).probes.filter { it.className == className }
        val deltas =
            registry
                .computeDeltaBatch(resource)
                .batch.deltas
                .associate { (it.classId to it.probeIndex) to it.hitsTotal }
        val totals = HashMap<Pair<String, ProbeKind>, Long>()
        for (p in probes) totals.merge(p.methodName to p.kind, deltas[p.classId to p.probeIndex] ?: 0L, Long::plus)
        return Counts(totals)
    }

    @Test
    fun `frame shapes run woven exactly as unwoven at every release and at version 50 with and without frames`() {
        val refusalsBefore = FrameRefusingClassWriter.refusals
        val parent = FramesShapesTest::class.java.classLoader
        val variants = variants()
        assertTrue(variants.size >= 3, "at least one release and both version 50 forms ran")
        for (variant in variants) {
            val registry = ProbeRegistry()
            val transformer = HotPathWeaver.offlineTransformer(listOf(PACKAGE), registry)
            val wovenLoader = FixtureLoader(parent, variant.classes, mutableMapOf())
            val twinLoader = FixtureLoader(parent, variant.classes, variant.classes.toMutableMap())
            for ((internalName, original) in variant.classes) {
                val woven =
                    checkNotNull(transformer.transform(wovenLoader, internalName, null, null, original)) {
                        "${variant.label}: $internalName was not woven"
                    }
                assertTrue(woven.size > original.size, "${variant.label}: $internalName gained probes")
                val originalFrames = frameCount(original)
                val wovenFrames = frameCount(woven)
                if (variant.label.endsWith("without frames")) {
                    // The probe-array accessor carries one frame, for its null test. The probes add none.
                    assertTrue(
                        frameCounts(woven).second <= 1,
                        "${variant.label}: $internalName gets no branch-tier frame where it had none",
                    )
                } else {
                    assertTrue(wovenFrames > originalFrames, "${variant.label}: $internalName gains a frame at the labels it inserts")
                }
                if (internalName.endsWith("/Big")) {
                    assertTrue(
                        longestCode(woven) > Short.MAX_VALUE && longestCode(original) < Short.MAX_VALUE,
                        "${variant.label}: weaving pushes the long method past 32 KB, onto GOTO_W",
                    )
                }
                wovenLoader.definitions[internalName] = woven
            }
            val shapes = "$PACKAGE.${variant.shapes}"
            val twinResult = run(Class.forName(shapes, true, twinLoader))
            val wovenResult = run(Class.forName(shapes, true, wovenLoader))
            assertEquals(twinResult, wovenResult, "${variant.label}: ${variant.shapes} woven against unwoven")
            val twinBig = Class.forName("$PACKAGE.Big", true, twinLoader).getMethod("big", Int::class.java)
            val wovenBig = Class.forName("$PACKAGE.Big", true, wovenLoader).getMethod("big", Int::class.java)
            for (n in listOf(0, 7, 40)) assertEquals(twinBig.invoke(null, n), wovenBig.invoke(null, n), "${variant.label}: big($n)")
            if (variant.label == "version 50 with frames") {
                val at51 = { bytes: ByteArray ->
                    bytes.copyOf().also {
                        it[6] = 0
                        it[7] = VERSION_WITHOUT_FALLBACK.toByte()
                    }
                }
                val strictTwin = FixtureLoader(parent, variant.classes, variant.classes.mapValues { at51(it.value) }.toMutableMap())
                val strictWoven =
                    FixtureLoader(parent, variant.classes, wovenLoader.definitions.mapValues { at51(it.value) }.toMutableMap())
                assertEquals(
                    run(Class.forName(shapes, true, strictTwin)),
                    run(Class.forName(shapes, true, strictWoven)),
                    "${variant.label}: the woven frames verify with no fallback at version 51",
                )
            }

            val shapeCounts = countsOf(registry, shapes)
            for (method in calls.keys.filter { it != "lambda" || variant.shapes == "Shapes" }) {
                if (method == "big") continue
                assertEquals(
                    calls.getValue(method).size.toLong(),
                    shapeCounts.of(method, ProbeKind.METHOD),
                    "${variant.label}: entries of $method",
                )
                if (method != "ctor" && method != "lambda") {
                    assertTrue(shapeCounts.of(method, ProbeKind.BRANCH) > 0, "${variant.label}: $method counted a branch")
                }
            }
            assertEquals(9L, shapeCounts.of("nullChecks", ProbeKind.BRANCH), "${variant.label}: three jumps in each of three calls")
            assertEquals(6L, shapeCounts.of("mergeTypes", ProbeKind.BRANCH), "${variant.label}: two jumps in each of three calls")
            val bigCounts = countsOf(registry, "$PACKAGE.Big")
            val expectedBig = listOf(0, 7, 40).sumOf { (it + 1L) + BIG_JUMPS * it }
            assertEquals(expectedBig, bigCounts.of("big", ProbeKind.BRANCH), "${variant.label}: the long method's jumps")
        }
        assertEquals(refusalsBefore, FrameRefusingClassWriter.refusals, "no weave asked the writer to compute a frame")
    }

    private companion object {
        const val PACKAGE = "com.example.frames"
        const val BIG_JUMPS = 300
        const val BIG_STRAIGHT_LINE = 2600
        const val VERSION_WITHOUT_FALLBACK = 51
    }
}
