package dev.otherlode.benchmark

import dev.otherlode.ClassFileSupport
import dev.otherlode.export.ProbeKind
import dev.otherlode.export.ProbeLocation
import dev.otherlode.export.ResourceAttributes
import dev.otherlode.instrumentation.FrameRefusingClassWriter
import dev.otherlode.instrumentation.VersionedFixtures
import dev.otherlode.registry.ProbeRegistry
import net.bytebuddy.jar.asm.ClassWriter
import net.bytebuddy.jar.asm.Label
import net.bytebuddy.jar.asm.Opcodes
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.URLClassLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the bytes the agent writes, since ByteBuddy's type validation is off. A woven class must
 * run where its unwoven twin runs, and fail where the twin fails with the same root exception and,
 * for a missing class, the same class name. Anything else is the agent's fault: the JVM's verifier
 * and class-file parser say so where validation would have guessed. Every woven class must also keep
 * the structure of its class file: header, inner classes, and the flags, signatures and annotations
 * of every field and method.
 *
 * Two inputs. Every class of the five benchmark corpora is woven through the real transformer, then
 * defined and initialised woven and unwoven, each in a loader of its own over the corpus's
 * classpath. And a matrix of generated classes at class-file versions 45 to 69 covers the shapes
 * the corpora, all compiled for recent versions, never contain: interfaces and annotation types
 * with a static initialiser, subroutines, annotations below version 49. Each of those is woven,
 * defined, initialised and called, woven and unwoven, and the woven copy must also count.
 */
class WovenClassVerificationTest {
    private val resource = ResourceAttributes("test", null, "instance-1", null, "run-1")

    /** What running one class did: its [result], or the [error] it ended in. */
    private data class Outcome(
        val result: List<String>,
        val error: String?,
        val rootType: Class<*>? = null,
        val rootMessage: String? = null,
    ) {
        val ok get() = error == null

        override fun toString(): String = error ?: result.toString()
    }

    private fun attempt(block: () -> List<String>): Outcome =
        try {
            Outcome(block(), null)
        } catch (t: Throwable) {
            val root = generateSequence(t) { it.cause }.last()
            Outcome(
                emptyList(),
                "${t.javaClass.simpleName}: ${t.message?.take(MESSAGE_WIDTH)} (root ${root.javaClass.simpleName})",
                root.javaClass,
                root.message,
            )
        }

    /**
     * Whether [woven] fails as [twin] does: the same root exception and, for a missing class, the
     * same class name, which `NoClassDefFoundError` spells with slashes and `ClassNotFoundException` with dots.
     */
    private fun failsLike(
        woven: Outcome,
        twin: Outcome,
    ): Boolean {
        if (woven.rootType != twin.rootType) return false
        val missingClass = woven.rootType == NoClassDefFoundError::class.java || woven.rootType == ClassNotFoundException::class.java
        return !missingClass || woven.rootMessage?.replace('/', '.') == twin.rootMessage?.replace('/', '.')
    }

    /**
     * Everything [bytes] says about the class apart from its code, keyed by what it describes: the
     * header (version, flags, name, signature, super and interfaces), `EnclosingMethod`, the nest and
     * permitted-subclass attributes, every `InnerClasses` entry in order, the source attributes, the
     * class annotations, the record components, and each field and method with its flags,
     * descriptor, signature, exceptions and annotations. The agent's own members are left out: the
     * probe field, its accessor methods, the refusal marker, and a `<clinit>` the class did not have.
     * Nest members and inner classes are compared in order, not as sets, because the decoration
     * writes them in the order the class file has and a reordering would still be a rewrite.
     */
    private fun structureOf(bytes: ByteArray): Map<String, String> {
        val node = ClassNode().also { ClassReader(bytes).accept(it, ClassReader.SKIP_CODE) }
        val structure = LinkedHashMap<String, String>()

        fun descriptors(annotations: List<AnnotationNode>?) = annotations.orEmpty().map { it.desc }

        structure["header"] =
            "version=${node.version} access=${node.access} name=${node.name} signature=${node.signature} " +
            "super=${node.superName} interfaces=${node.interfaces}"
        structure["enclosing method"] = "${node.outerClass} ${node.outerMethod} ${node.outerMethodDesc}"
        structure["nest"] = "host=${node.nestHostClass} members=${node.nestMembers} permitted=${node.permittedSubclasses}"
        structure["inner classes"] = node.innerClasses.joinToString { "(${it.name},${it.outerName},${it.innerName},${it.access})" }
        structure["source"] = "${node.sourceFile} ${node.sourceDebug}"
        structure["annotations"] =
            "${descriptors(node.visibleAnnotations)} ${descriptors(node.invisibleAnnotations)} " +
            "${node.visibleTypeAnnotations.orEmpty().map { it.desc + it.typeRef }} " +
            "${node.invisibleTypeAnnotations.orEmpty().map { it.desc + it.typeRef }}"
        structure["record components"] = "${node.recordComponents?.map { it.name + it.descriptor + it.signature }}"
        for (field in node.fields) {
            if (field.name.startsWith(AGENT_MEMBER_PREFIX)) continue
            structure["field ${field.name} ${field.desc}"] =
                "access=${field.access} signature=${field.signature} value=${field.value} " +
                "${descriptors(field.visibleAnnotations)} ${descriptors(field.invisibleAnnotations)}"
        }
        for (method in node.methods) {
            if (method.name.startsWith(AGENT_MEMBER_PREFIX)) continue
            structure["method ${method.name}${method.desc}"] =
                "access=${method.access} signature=${method.signature} exceptions=${method.exceptions} " +
                "${descriptors(method.visibleAnnotations)} ${descriptors(method.invisibleAnnotations)} " +
                "parameter annotations=${method.visibleParameterAnnotations?.map { list -> descriptors(list) }} " +
                "parameters=${method.parameters?.map { it.name + it.access }} default=${render(method.annotationDefault)}"
        }
        return structure
    }

    /**
     * An annotation default as text, by content: ASM holds an enum default as a `String[]` and a nested
     * annotation as an [AnnotationNode], neither of which prints its content.
     */
    private fun render(value: Any?): String =
        when (value) {
            null -> "null"
            is Array<*> -> value.joinToString(prefix = "[", postfix = "]") { render(it) }
            is List<*> -> value.joinToString(prefix = "[", postfix = "]") { render(it) }
            is AnnotationNode -> "@${value.desc}${render(value.values)}"
            else -> value.toString()
        }

    /**
     * What weaving and running one corpus found: how many classes wove, ran in both forms or failed in both,
     * how many the agent refused for an absent supertype, and the violations.
     */
    private class CorpusResult(
        val corpus: String,
        val classes: Int,
        val woven: Int,
        val bothRan: Int,
        val bothFailed: Int,
        val refused: Int,
        val violations: List<String>,
    )

    /** A loader that defines [name] from [bytes] and takes every other class from [parent]. */
    private class DefineOneLoader(
        parent: ClassLoader,
        private val name: String,
        private val bytes: ByteArray,
    ) : ClassLoader(parent) {
        override fun loadClass(
            requested: String,
            resolve: Boolean,
        ): Class<*> {
            if (requested != name) return super.loadClass(requested, resolve)
            synchronized(getClassLoadingLock(requested)) {
                return findLoadedClass(requested) ?: defineClass(requested, bytes, 0, bytes.size)
            }
        }
    }

    private fun sweep(corpusName: String): CorpusResult {
        val corpus = BenchmarkCorpus.load(corpusName)
        var woven = 0
        var bothRan = 0
        var bothFailed = 0
        var refusedForSupertype = 0
        val violations = mutableListOf<String>()
        for (set in corpus.classSets) {
            val urls = CorpusWeaving.classSetUrls(corpusName, set)
            URLClassLoader(urls.toTypedArray(), ClassLoader.getPlatformClassLoader()).use { shared ->
                val transformer = HotPathWeaver.offlineTransformer(corpus.includePackages, ProbeRegistry())
                for ((internalName, original) in set.classes) {
                    val wovenBytes =
                        try {
                            transformer.transform(shared, internalName, null, null, original)
                        } catch (t: Throwable) {
                            // One reason lets a class stay unwoven: its own supertype is absent from the classpath,
                            // so the JVM could not define it unwoven either (ADR 0059). Anything else is a weave the
                            // agent got wrong.
                            val reason = generateSequence(t) { it.cause }.map { it.message.orEmpty() }.joinToString(" <- ")
                            val name = internalName.replace('/', '.')
                            when {
                                SUPERTYPE_REFUSAL.containsMatchIn(reason) -> {
                                    val twin = attempt { initialise(DefineOneLoader(shared, name, original), name) }
                                    if (twin.error?.startsWith("NoClassDefFoundError") == true) {
                                        refusedForSupertype++
                                    } else {
                                        violations +=
                                            "${set.name}:$name was refused for an absent supertype but its unwoven twin gives: $twin"
                                    }
                                }

                                else -> {
                                    violations += "${set.name}:$internalName failed to weave: $reason"
                                }
                            }
                            null
                        } ?: continue
                    woven++
                    val name = internalName.replace('/', '.')
                    val unwovenOutcome = attempt { initialise(DefineOneLoader(shared, name, original), name) }
                    val wovenOutcome = attempt { initialise(DefineOneLoader(shared, name, wovenBytes), name) }
                    structureDifferences(original, wovenBytes).forEach { violations += "${set.name}:$name $it" }
                    when {
                        wovenOutcome.ok && unwovenOutcome.ok -> {
                            bothRan++
                        }

                        !wovenOutcome.ok && !unwovenOutcome.ok && failsLike(wovenOutcome, unwovenOutcome) -> {
                            bothFailed++
                        }

                        else -> {
                            // Fails where its twin runs, or runs where its twin fails: the weave changed what the JVM checks (ADR 0061).
                            violations += "${set.name}:$name woven: $wovenOutcome; unwoven: $unwovenOutcome"
                        }
                    }
                }
            }
        }
        return CorpusResult(corpusName, corpus.classCount, woven, bothRan, bothFailed, refusedForSupertype, violations)
    }

    /**
     * What differs between [original] and [woven], code aside: see [structureOf]. A woven class below
     * class-file version 55 that gains a `<clinit>` is the agent's own addition (the probe array's
     * prelude, ADR 0060) and is not a difference; from 55 the agent adds none, so one is.
     */
    private fun structureDifferences(
        original: ByteArray,
        woven: ByteArray,
    ): List<String> {
        val before = structureOf(original)
        val addsPrelude = ((original[6].toInt() and 0xff) shl 8 or (original[7].toInt() and 0xff)) < DYNAMIC_CONSTANT_VERSION
        val after = structureOf(woven).filterKeys { it != TYPE_INITIALIZER || it in before || !addsPrelude }
        val differences = mutableListOf<String>()
        for ((key, value) in after) {
            if (before[key] != value) differences += "$key is ${before[key]} in the class file and $value woven"
        }
        for (key in before.keys - after.keys) differences += "$key is missing from the woven class"
        return differences
    }

    private fun initialise(
        loader: ClassLoader,
        name: String,
    ): List<String> {
        Class.forName(name, true, loader)
        return emptyList()
    }

    @Test
    fun `every woven class of the benchmark corpora defines and initialises wherever its unwoven twin does`() {
        val started = System.nanoTime()
        val refusalsBefore = FrameRefusingClassWriter.refusals
        val results = CorpusWeaving.CORPORA.map { sweep(it) }
        assertEquals(refusalsBefore, FrameRefusingClassWriter.refusals, "no weave of any corpus class asked the writer to compute a frame")
        println(
            "woven-class verification, corpora: " +
                results.joinToString("; ") {
                    "${it.corpus} classes=${it.classes} woven=${it.woven} bothRan=${it.bothRan} bothFailed=${it.bothFailed} refused=${it.refused}"
                } + " (${(System.nanoTime() - started) / NANOS_PER_MILLI} ms)",
        )
        for (r in results) {
            assertTrue(
                r.violations.isEmpty(),
                "${r.corpus}: woven classes that fail where the unwoven class does not:\n  ${r.violations.joinToString("\n  ")}",
            )
            // Floors a little under what each corpus gives, so a weave or a run that quietly stops
            // happening fails here rather than shrinking what the check covers.
            val (minWoven, minRan) = FLOORS.getValue(r.corpus)
            assertTrue(r.woven >= minWoven, "${r.corpus}: only ${r.woven} of ${r.classes} classes were woven, expected at least $minWoven")
            assertTrue(r.bothRan >= minRan, "${r.corpus}: only ${r.bothRan} of ${r.woven} woven classes ran, expected at least $minRan")
        }
    }

    @Test
    fun `spring-core's ReactorDelegate without Reactor fails woven exactly as it does unwoven`() {
        // PropagationContextElement$ReactorDelegate types a local Reactor's ContextView, and Reactor is not on the
        // classpath. Unwoven, the JVM's check of its frame fails with NoClassDefFoundError. Woven, it must weave and
        // fail the same way, since the woven method keeps the class file's frame (ADR 0061); a woven class that
        // defined would hand a framework probing for Reactor a false positive.
        val classpath =
            checkNotNull(System.getProperty("${CorpusWeaving.CLASSPATH_PROPERTY}demo-spring.main")) { "no classpath for demo-spring" }
        val entries = classpath.split(File.pathSeparator).filter { it.isNotEmpty() }
        assertTrue(entries.none { File(it).name.startsWith("reactor-core") }, "Reactor must be absent for this check")
        val internalName = "org/springframework/core/PropagationContextElement\$ReactorDelegate"
        val jar = entries.first { File(it).name.startsWith("spring-core-") }
        val original =
            java.util.zip.ZipFile(jar).use { zip ->
                zip
                    .getInputStream(
                        checkNotNull(zip.getEntry("$internalName.class")) { "$internalName is not in $jar" },
                    ).use { it.readBytes() }
            }
        val name = internalName.replace('/', '.')
        URLClassLoader(entries.map { File(it).toURI().toURL() }.toTypedArray(), ClassLoader.getPlatformClassLoader()).use { shared ->
            val transformer = HotPathWeaver.offlineTransformer(listOf("org.springframework"), ProbeRegistry())
            val wovenBytes = checkNotNull(transformer.transform(shared, internalName, null, null, original)) { "$name was not woven" }
            val unwoven = attempt { initialise(DefineOneLoader(shared, name, original), name) }
            val woven = attempt { initialise(DefineOneLoader(shared, name, wovenBytes), name) }
            assertTrue(!unwoven.ok, "unwoven, $name fails without Reactor, or the check proves nothing")
            assertTrue(failsLike(woven, unwoven), "woven: $woven; unwoven: $unwoven")
            assertEquals(unwoven.error, woven.error, "woven: $woven; unwoven: $unwoven")
        }
    }

    /** `static Object m(boolean b)` with a frame that types local 1 as a class no loader has, at a branch target a String reaches. */
    private fun absentTypeInFrame(internalName: String): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER, internalName, null, "java/lang/Object", null)
        cw.visitSource("Absent.java", null)
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "m", "(Z)Ljava/lang/Object;", null, null)
        mv.visitCode()
        val start = Label()
        mv.visitLabel(start)
        mv.visitLineNumber(3, start)
        mv.visitLdcInsn("a")
        mv.visitVarInsn(Opcodes.ASTORE, 1)
        mv.visitVarInsn(Opcodes.ILOAD, 0)
        val target = Label()
        mv.visitJumpInsn(Opcodes.IFEQ, target)
        mv.visitVarInsn(Opcodes.ALOAD, 1)
        mv.visitInsn(Opcodes.ARETURN)
        mv.visitLabel(target)
        mv.visitFrame(Opcodes.F_FULL, 2, arrayOf<Any>(Opcodes.INTEGER, "absent/Missing"), 0, emptyArray())
        mv.visitVarInsn(Opcodes.ALOAD, 1)
        mv.visitInsn(Opcodes.ARETURN)
        mv.visitMaxs(1, 2)
        mv.visitEnd()
        cw.visitEnd()
        return cw.toByteArray()
    }

    @Test
    fun `a frame that names a type no loader has is kept, so the woven class fails verification as its twin does`() {
        val internalName = "com/example/absent/Absent"
        val original = absentTypeInFrame(internalName)
        val registry = ProbeRegistry()
        val transformer = HotPathWeaver.offlineTransformer(listOf("com.example.absent"), registry)
        val parent = WovenClassVerificationTest::class.java.classLoader
        val twinLoader = MatrixLoader(parent, mapOf(internalName to original), mutableMapOf(internalName to original))
        val wovenLoader = MatrixLoader(parent, mapOf(internalName to original), mutableMapOf())
        wovenLoader.definitions[internalName] =
            checkNotNull(transformer.transform(wovenLoader, internalName, null, null, original)) { "not woven" }
        val name = internalName.replace('/', '.')

        val twin = attempt { initialise(twinLoader, name) }
        val woven = attempt { initialise(wovenLoader, name) }

        assertTrue(twin.error.orEmpty().startsWith("NoClassDefFoundError: absent/Missing"), "unwoven: $twin")
        assertEquals(twin.error, woven.error, "woven: $woven; unwoven: $twin")
    }

    /**
     * A loader that serves [resources] as class files and [definitions] as the bytes it defines
     * classes from. The agent reads a class file as a resource, so weaving through a loader with
     * only the originals in [resources] reads the compiler's bytes, and the woven ones can then be
     * put in [definitions] for the same loader to define.
     */
    private class MatrixLoader(
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

    /** One generated shape: its classes, how to run it, and what its probes must have counted. */
    private class Case(
        val label: String,
        val classes: List<VersionedFixtures.Fixture>,
        val drive: (ClassLoader) -> List<String>,
        val expect: (Counts) -> Unit,
    )

    /** The hits of one run, read from the registry the woven classes counted into. */
    private class Counts(
        private val probes: List<ProbeLocation>,
        private val hits: Map<Pair<Int, Int>, Long>,
    ) {
        private fun hitsOf(probe: ProbeLocation): Long = hits[probe.classId to probe.probeIndex] ?: 0L

        private fun of(
            className: String,
            methodName: String,
            kind: ProbeKind,
        ) = probes.filter { it.className == className && it.methodName == methodName && it.kind == kind }

        /** How often [methodName] of [className] was entered. */
        fun entries(
            className: String,
            methodName: String,
        ): Long = of(className, methodName, ProbeKind.METHOD).sumOf { hitsOf(it) }

        /** How many branch outcomes [methodName] of [className] has probes for. */
        fun branchProbes(
            className: String,
            methodName: String,
        ): Int = of(className, methodName, ProbeKind.BRANCH).size

        /** How many branch outcomes of [methodName] of [className] were taken in all. */
        fun branchHits(
            className: String,
            methodName: String,
        ): Long = of(className, methodName, ProbeKind.BRANCH).sumOf { hitsOf(it) }
    }

    private fun cases(version: Int): List<Case> {
        val cases = mutableListOf<Case>()
        val plain = VersionedFixtures.plain(version)
        cases +=
            Case(
                "a class with a static initialiser and a branch",
                listOf(plain),
                { loader ->
                    val type = Class.forName(plain.name, true, loader)
                    val target = type.getDeclaredConstructor().newInstance()
                    listOf(
                        "counter=" + type.getField("counter").get(null),
                        "pick(5)=" + type.getMethod("pick", Int::class.java).invoke(target, 5),
                        "pick(-5)=" + type.getMethod("pick", Int::class.java).invoke(target, -5),
                        "pick(0)=" + type.getMethod("pick", Int::class.java).invoke(target, 0),
                    )
                },
            ) { counts ->
                assertEquals(1L, counts.entries(plain.name, "<clinit>"), "the static initialiser ran once")
                assertEquals(3L, counts.entries(plain.name, "pick"))
                assertEquals(2, counts.branchProbes(plain.name, "pick"), "both outcomes of the one branch")
                assertEquals(3L, counts.branchHits(plain.name, "pick"))
            }

        val straight = VersionedFixtures.straight(version)
        cases +=
            Case(
                "a class with neither a branch nor a static initialiser",
                listOf(straight),
                { loader -> listOf("seven=" + Class.forName(straight.name, true, loader).getMethod("seven").invoke(null)) },
            ) { counts -> assertEquals(1L, counts.entries(straight.name, "seven")) }

        val constants = VersionedFixtures.constants(version)
        cases +=
            Case(
                "an interface with a static field its initialiser sets",
                listOf(constants),
                { loader -> listOf("LIMIT=" + Class.forName(constants.name, true, loader).getField("LIMIT").get(null)) },
            ) { counts -> assertEquals(1L, counts.entries(constants.name, "<clinit>"), "the interface's static initialiser ran once") }

        if (version >= 52) {
            val (defaults, implementation) = VersionedFixtures.defaults(version)
            cases +=
                Case(
                    "an interface with a default and a static method",
                    listOf(defaults, implementation),
                    { loader ->
                        val iface = Class.forName(defaults.name, true, loader)
                        val impl = Class.forName(implementation.name, true, loader).getDeclaredConstructor().newInstance()
                        val twice = iface.getMethod("twice", Int::class.java)
                        listOf(
                            "twice(4)=" + twice.invoke(impl, 4),
                            "twice(-4)=" + twice.invoke(impl, -4),
                            "zero=" + iface.getMethod("zero").invoke(null),
                        )
                    },
                ) { counts ->
                    assertEquals(2L, counts.entries(defaults.name, "twice"))
                    assertEquals(1L, counts.entries(defaults.name, "zero"))
                    assertEquals(2, counts.branchProbes(defaults.name, "twice"))
                    assertEquals(2L, counts.branchHits(defaults.name, "twice"))
                }
        }

        if (version >= 49) {
            val marker = VersionedFixtures.annotationType(version)
            val carrier = VersionedFixtures.annotated(version, marker)
            cases +=
                Case(
                    "an annotation type with a static initialiser, read from a class it annotates",
                    listOf(marker, carrier),
                    { loader ->
                        val markerType = Class.forName(marker.name, true, loader)

                        @Suppress("UNCHECKED_CAST")
                        val annotation = Class.forName(carrier.name, true, loader).getAnnotation(markerType as Class<Annotation>)
                        listOf(
                            "LEVEL=" + markerType.getField("LEVEL").get(null),
                            "level=" + markerType.getMethod("level").invoke(annotation),
                            "toString=" + annotation.toString().substringBefore('('),
                            "hashCode=" + annotation.hashCode(),
                            "equals=" + (annotation == annotation),
                        )
                    },
                ) { counts -> assertEquals(1L, counts.entries(marker.name, "<clinit>")) }
        }

        if (version < 51) {
            val subroutine = VersionedFixtures.subroutine(version)
            cases +=
                Case(
                    "a method calling a subroutine that holds a branch",
                    listOf(subroutine),
                    { loader ->
                        val type = Class.forName(subroutine.name, true, loader)
                        val f = type.getMethod("f", Int::class.java)
                        listOf(
                            "f(5)=" + f.invoke(null, 5),
                            "f(0)=" + f.invoke(null, 0),
                            "f(-1)=" + f.invoke(null, -1),
                            "hits=" + type.getField("hits").get(null),
                        )
                    },
                ) { counts ->
                    assertEquals(3L, counts.entries(subroutine.name, "f"))
                    assertEquals(
                        6,
                        counts.branchProbes(subroutine.name, "f"),
                        "three conditionals, the one in the subroutine copied for each caller",
                    )
                    assertEquals(
                        6L,
                        counts.branchHits(subroutine.name, "f"),
                        "two conditionals taken in each of three calls, one of them inside a copy",
                    )
                }
        }

        if (version <= 48) {
            val annotated = VersionedFixtures.annotated(version)
            cases +=
                Case(
                    "a class carrying a RuntimeVisibleAnnotations attribute",
                    listOf(annotated),
                    { loader ->
                        val type = Class.forName(annotated.name, true, loader)
                        val target = type.getDeclaredConstructor().newInstance()
                        listOf("pick(2)=" + type.getMethod("pick", Int::class.java).invoke(target, 2))
                    },
                ) { counts ->
                    assertEquals(1L, counts.entries(annotated.name, "pick"))
                    assertEquals(1L, counts.branchHits(annotated.name, "pick"))
                }
        }
        return cases
    }

    @Test
    fun `generated classes at every class-file version define, initialise, run and count woven as they do unwoven`() {
        val started = System.nanoTime()
        val supported = ClassFileSupport.newestDefinableVersion
        val skipped = VersionedFixtures.VERSIONS.filter { it > supported }
        val failures = mutableListOf<String>()
        var covered = 0
        val perVersion = sortedMapOf<Int, Int>()
        for (version in VersionedFixtures.VERSIONS - skipped.toSet()) {
            for (case in cases(version)) {
                covered++
                perVersion.merge(version, 1, Int::plus)
                failures += runCase(version, case)
            }
        }
        println(
            "woven-class verification, matrix: fixtures per version $perVersion, skipped versions $skipped on JDK " +
                "${Runtime.version().feature()}, $covered cases (${(System.nanoTime() - started) / NANOS_PER_MILLI} ms)",
        )
        assertTrue(failures.isEmpty(), "woven classes that differ from their unwoven twins:\n  ${failures.joinToString("\n  ")}")
        assertTrue(covered > 0)
    }

    /** What went wrong running [case] woven against its unwoven twin at [version], or nothing. */
    private fun runCase(
        version: Int,
        case: Case,
    ): List<String> {
        val label = "v$version ${case.label}"
        val originals = case.classes.associate { it.internalName to it.bytes }
        val registry = ProbeRegistry()
        val transformer = HotPathWeaver.offlineTransformer(listOf(VersionedFixtures.PACKAGE), registry)
        val parent = WovenClassVerificationTest::class.java.classLoader
        val wovenLoader = MatrixLoader(parent, originals, mutableMapOf())
        val twinLoader = MatrixLoader(parent, originals, originals.toMutableMap())
        for (fixture in case.classes) {
            val bytes =
                try {
                    transformer.transform(wovenLoader, fixture.internalName, null, null, fixture.bytes)
                } catch (t: Throwable) {
                    return listOf("$label: weaving ${fixture.name} failed: $t")
                } ?: return listOf("$label: ${fixture.name} was not woven")
            wovenLoader.definitions[fixture.internalName] = bytes
        }
        val twin = attempt { case.drive(twinLoader) }
        val woven = attempt { case.drive(wovenLoader) }
        if (!woven.ok && twin.ok) return listOf("$label: woven fails with ${woven.error}; unwoven runs and gives ${twin.result}")
        if (!woven.ok) {
            return listOf(
                "$label: both fail (woven: ${woven.error}; unwoven: ${twin.error}), so nothing here shows the weave is sound",
            )
        }
        if (woven.result != twin.result) return listOf("$label: woven gives ${woven.result}; unwoven gives ${twin.result}")
        return try {
            case.expect(countsOf(registry))
            emptyList()
        } catch (e: AssertionError) {
            listOf("$label: ${e.message}")
        }
    }

    private fun countsOf(registry: ProbeRegistry): Counts {
        val probes = registry.manifest(resource).probes
        val deltas = registry.computeDeltaBatch(resource).batch.deltas
        return Counts(probes, deltas.associate { (it.classId to it.probeIndex) to it.hitsTotal })
    }

    private companion object {
        const val AGENT_MEMBER_PREFIX = "\$otherlode"
        const val TYPE_INITIALIZER = "method <clinit>()V"
        const val DYNAMIC_CONSTANT_VERSION = 55
        val SUPERTYPE_REFUSAL = Regex("cannot be defined: its supertype \\S+ could not be read from its loader")

        /** Per corpus, the fewest classes that must weave and the fewest that must run in both forms. */
        val FLOORS =
            mapOf(
                "demo" to (78 to 77),
                "demo-spring" to (9 to 9),
                "scala" to (303 to 303),
                "spring-webmvc" to (468 to 414),
                "ktor-server-core" to (412 to 401),
            )
        const val MESSAGE_WIDTH = 160
        const val NANOS_PER_MILLI = 1_000_000
    }
}
