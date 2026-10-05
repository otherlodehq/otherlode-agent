package dev.otherlode.instrumentation

import dev.otherlode.benchmark.HotPathWeaver
import dev.otherlode.config.AgentConfig
import dev.otherlode.export.ProbeKind
import dev.otherlode.export.ResourceAttributes
import dev.otherlode.registry.ProbeRegistry
import net.bytebuddy.agent.ByteBuddyAgent
import net.bytebuddy.dynamic.ClassFileLocator
import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.Opcodes
import net.bytebuddy.pool.TypePool
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.lang.instrument.ClassFileTransformer
import java.lang.reflect.InvocationTargetException
import java.util.function.IntUnaryOperator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import java.util.logging.Handler as JulHandler
import java.util.logging.Level as JulLevel
import java.util.logging.LogRecord as JulLogRecord
import java.util.logging.Logger as JulLogger

/**
 * A class that names a type the classpath lacks in a signature weaves and runs as it does without
 * the agent, and keeps the header its class file has. A class whose own supertype is absent cannot
 * be defined by the JVM at all and is refused. Classes are written with ASM and woven by the agent's
 * real transformer; no loader provides the types they name.
 */
class AbsentTypeWeavingTest {
    private val resource = ResourceAttributes("test", null, "instance-1", null, "run-1")
    private val registry = ProbeRegistry()
    private val transformer = HotPathWeaver.offlineTransformer(listOf(AbsentTypeFixtures.PACKAGE), registry)

    private fun internal(simpleName: String) = AbsentTypeFixtures.PACKAGE.replace('.', '/') + "/" + simpleName

    /** Serves [classFiles] as resources and defines the bytes put in [defined]. */
    private class InMemoryLoader(
        private val classFiles: Map<String, ByteArray>,
    ) : ClassLoader(AbsentTypeWeavingTest::class.java.classLoader) {
        val defined = HashMap<String, ByteArray>()

        override fun findClass(name: String): Class<*> {
            val bytes = defined[name] ?: throw ClassNotFoundException(name)
            return defineClass(name, bytes, 0, bytes.size)
        }

        override fun getResourceAsStream(name: String): InputStream? {
            val className = name.removeSuffix(".class").replace('/', '.')
            return classFiles[className]?.let { ByteArrayInputStream(it) } ?: super.getResourceAsStream(name)
        }
    }

    private fun loaderOf(vararg classes: Pair<String, ByteArray>) = InMemoryLoader(classes.associate { (n, b) -> n.replace('/', '.') to b })

    @Test
    fun `a class naming an absent generic type in a field and a return type weaves, defines, runs and counts`() {
        val name = internal("Holder")
        val original = AbsentTypeFixtures.holder(name)
        val loader = loaderOf(name to original)

        val woven = assertNotNull(transformer.transform(loader, name, null, null, original))
        loader.defined[name.replace('/', '.')] = woven
        val type = loader.loadClass(name.replace('/', '.'))
        val instance = type.getDeclaredConstructor().newInstance() as IntUnaryOperator

        assertEquals(1, instance.applyAsInt(5))
        assertEquals(2, instance.applyAsInt(-5))
        val probes = registry.manifest(resource).probes.filter { it.methodName == "applyAsInt" }
        assertEquals(1, probes.count { it.kind == ProbeKind.METHOD })
        assertEquals(2, probes.count { it.kind == ProbeKind.BRANCH })
    }

    @Test
    fun `a class whose superclass is absent is refused and reported skipped, not woven`() {
        val name = internal("Orphan")
        val original = AbsentTypeFixtures.subtype(name, AbsentTypeFixtures.ABSENT_PREFIX + "Parent")

        val failure = assertFailsWith<Throwable> { transformer.transform(loaderOf(name to original), name, null, null, original) }

        assertSkippedForSupertype(name, "com.example.absent.Parent", failure)
    }

    @Test
    fun `a class whose interface is absent is refused and reported skipped, not woven`() {
        val name = internal("Implementor")
        val original = AbsentTypeFixtures.subtype(name, "java/lang/Object", AbsentTypeFixtures.ABSENT_PREFIX + "Contract")

        val failure = assertFailsWith<Throwable> { transformer.transform(loaderOf(name to original), name, null, null, original) }

        assertSkippedForSupertype(name, "com.example.absent.Contract", failure)
    }

    @Test
    fun `a class whose grandparent is absent is refused too`() {
        val middle = internal("Middle")
        val leaf = internal("Leaf")
        val middleBytes = AbsentTypeFixtures.subtype(middle, AbsentTypeFixtures.ABSENT_PREFIX + "Root")
        val leafBytes = AbsentTypeFixtures.subtype(leaf, middle)

        val failure =
            assertFailsWith<Throwable> {
                transformer.transform(loaderOf(middle to middleBytes, leaf to leafBytes), leaf, null, null, leafBytes)
            }

        assertSkippedForSupertype(leaf, "com.example.absent.Root", failure)
    }

    @Test
    fun `a class whose superclass is present but implements an absent interface is refused too`() {
        val parent = internal("Parent")
        val child = internal("Child")
        val parentBytes = AbsentTypeFixtures.subtype(parent, "java/lang/Object", AbsentTypeFixtures.ABSENT_PREFIX + "Contract")
        val childBytes = AbsentTypeFixtures.subtype(child, parent)

        val failure =
            assertFailsWith<Throwable> {
                transformer.transform(loaderOf(parent to parentBytes, child to childBytes), child, null, null, childBytes)
            }

        assertSkippedForSupertype(child, "com.example.absent.Contract", failure)
    }

    @Test
    fun `a refusal for an absent supertype is one WARNING naming the class and the supertype, with no stack trace`() {
        val name = internal("Orphan")
        val original = AbsentTypeFixtures.subtype(name, AbsentTypeFixtures.ABSENT_PREFIX + "Parent")

        val records =
            captureLogRecords(OtherlodeInstrumentation::class.java.name) {
                assertFailsWith<Throwable> { transformer.transform(loaderOf(name to original), name, null, null, original) }
            }

        val warning = records.single { it.level == JulLevel.WARNING }
        assertTrue("Orphan" in warning.message && "com.example.absent.Parent" in warning.message, warning.message)
        assertTrue("cannot be defined" in warning.message, warning.message)
        assertEquals(null, warning.thrown)
    }

    @Test
    fun `a method whose frame merges two types its loader defines is woven, runs and counts as it does unwoven`() {
        val merge = AbsentTypeFixtures.classMerge(AbsentTypeFixtures.PACKAGE.replace('.', '/'))
        val loader = loaderOf(merge.chooser)
        for ((name, bytes) in listOf(merge.base, merge.first, merge.second)) loader.defined[name.replace('/', '.')] = bytes
        val chooserName = merge.chooser.first.replace('/', '.')

        loader.defined[chooserName] =
            assertNotNull(transformer.transform(loader, merge.chooser.first, null, null, merge.chooser.second))
        val pick = loader.loadClass(chooserName).getMethod("pick", Int::class.java)

        assertEquals("A", pick.invoke(null, 5).javaClass.simpleName)
        assertEquals("B", pick.invoke(null, -5).javaClass.simpleName)
        assertTrue(registry.manifest(resource).skippedClasses.isEmpty())
        assertTrue(registry.manifest(resource).probes.any { it.className == chooserName && it.methodName == "pick" })
    }

    @Test
    fun `a class its loader defines from memory without serving its class file is woven and runs as it does unwoven`() {
        val merge = AbsentTypeFixtures.classMerge(AbsentTypeFixtures.PACKAGE.replace('.', '/'))
        val loader = loaderOf()
        for ((name, bytes) in listOf(merge.base, merge.first, merge.second)) loader.defined[name.replace('/', '.')] = bytes
        val chooserName = merge.chooser.first.replace('/', '.')

        loader.defined[chooserName] =
            assertNotNull(transformer.transform(loader, merge.chooser.first, null, null, merge.chooser.second))
        val pick = loader.loadClass(chooserName).getMethod("pick", Int::class.java)

        assertEquals("A", pick.invoke(null, 5).javaClass.simpleName)
        assertEquals("B", pick.invoke(null, -5).javaClass.simpleName)
    }

    @Test
    fun `a woven class whose merged types no loader has fails as its unwoven twin does`() {
        val merge = AbsentTypeFixtures.classMerge(AbsentTypeFixtures.PACKAGE.replace('.', '/'), "java/lang/Object")
        val chooserName = merge.chooser.first.replace('/', '.')
        val twin = loaderOf(merge.chooser)
        twin.defined[chooserName] = merge.chooser.second
        val woven = loaderOf(merge.chooser)
        woven.defined[chooserName] =
            assertNotNull(transformer.transform(woven, merge.chooser.first, null, null, merge.chooser.second))

        val twinFailure = failureOfPick(twin, chooserName)
        val wovenFailure = failureOfPick(woven, chooserName)

        assertEquals(twinFailure.message, wovenFailure.message)
        assertTrue(registry.manifest(resource).skippedClasses.isEmpty())
    }

    private fun failureOfPick(
        loader: ClassLoader,
        className: String,
    ): NoClassDefFoundError {
        val failure =
            assertFailsWith<InvocationTargetException> { loader.loadClass(className).getMethod("pick", Int::class.java).invoke(null, 5) }
        return failure.targetException as NoClassDefFoundError
    }

    @Test
    fun `a loader that serves no class file for the class still weaves it, and it runs`() {
        val name = internal("Holder")
        val original = AbsentTypeFixtures.holder(name)
        val withoutFiles = loaderOf()

        withoutFiles.defined[name.replace('/', '.')] = assertNotNull(transformer.transform(withoutFiles, name, null, null, original))
        val instance = withoutFiles.loadClass(name.replace('/', '.')).getDeclaredConstructor().newInstance() as IntUnaryOperator

        assertEquals(1, instance.applyAsInt(5))
        assertEquals(2, instance.applyAsInt(-5))
    }

    @Test
    fun `a woven class takes its class signature from the bytes received, not from the class file an earlier transformer changed`() {
        val name = internal("Resigned")
        val classFile = AbsentTypeFixtures.signed(name, "<T::L${AbsentTypeFixtures.ABSENT_PREFIX}Iface;>Ljava/lang/Object;")
        val receivedSignature = "<T::L${AbsentTypeFixtures.ABSENT_PREFIX}Other;>Ljava/lang/Object;"
        val received = AbsentTypeFixtures.signed(name, receivedSignature)
        val captured = mutableListOf<ClassFileTransformer>()
        OtherlodeInstrumentation(
            AgentConfig.parse("includePackages=${AbsentTypeFixtures.PACKAGE}"),
            registry,
            captureClassBytes = true,
        ).install(HotPathWeaver.capturing(ByteBuddyAgent.install(), captured))
        val loader = loaderOf(name to classFile)

        captured.first().transform(loader, name, null, null, received)
        val woven = assertNotNull(captured.last().transform(loader, name, null, null, received))

        assertEquals(receivedSignature, classSignatureOf(woven))
    }

    @Test
    fun `the class signature of a woven class is its class file's, an interface bound on an absent type included`() {
        val name = internal("Bounded")
        val signature = "<T::L${AbsentTypeFixtures.ABSENT_PREFIX}Iface;>Ljava/lang/Object;"

        assertEquals(signature, wovenClassSignature(name, AbsentTypeFixtures.signed(name, signature)))
    }

    @Test
    fun `the class signature of a woven class keeps the inner type's simple name`() {
        val name = internal("Nested")
        val signature = "<T::L${AbsentTypeFixtures.ABSENT_PREFIX}Outer<Ljava/lang/String;>.Inner;>Ljava/lang/Object;"

        assertEquals(signature, wovenClassSignature(name, AbsentTypeFixtures.signed(name, signature)))
    }

    private fun captureLogRecords(
        loggerName: String,
        block: () -> Unit,
    ): List<JulLogRecord> {
        val records = mutableListOf<JulLogRecord>()
        val handler =
            object : JulHandler() {
                override fun publish(record: JulLogRecord) {
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

    private fun wovenClassSignature(
        name: String,
        original: ByteArray,
    ): String? = classSignatureOf(assertNotNull(transformer.transform(loaderOf(name to original), name, null, null, original)))

    private fun classSignatureOf(woven: ByteArray): String? {
        var signature: String? = null
        ClassReader(woven).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visit(
                    version: Int,
                    access: Int,
                    name: String?,
                    signature0: String?,
                    superName: String?,
                    interfaces: Array<out String>?,
                ) {
                    signature = signature0
                }
            },
            ClassReader.SKIP_CODE,
        )
        return signature
    }

    @Test
    fun `a class whose supertypes are all present is woven`() {
        val parent = internal("Parent")
        val child = internal("Child")
        val parentBytes = AbsentTypeFixtures.subtype(parent, "java/lang/Object", "java/lang/Runnable")
        val childBytes = AbsentTypeFixtures.subtype(child, parent)

        val woven = transformer.transform(loaderOf(parent to parentBytes, child to childBytes), child, null, null, childBytes)

        assertNotNull(woven)
        assertTrue(registry.manifest(resource).skippedClasses.isEmpty())
    }

    private fun assertSkippedForSupertype(
        className: String,
        supertype: String,
        failure: Throwable,
    ) {
        val dotted = className.replace('/', '.')
        val causes = generateSequence(failure) { it.cause }.joinToString(" <- ") { it.message.orEmpty() }
        assertTrue("its supertype $supertype could not be read from its loader" in causes, causes)
        val skipped = registry.manifest(resource).skippedClasses.single { it.className == dotted }
        assertTrue(supertype in skipped.reason, skipped.reason)
        assertTrue(registry.manifest(resource).probes.none { it.className == dotted }, "no probe of a class that never defines")
    }

    /** Serves no class file and defines only what a test puts in [defined], as a loader does for bytes it holds in memory. */
    private fun memoryLoader() = loaderOf()

    private fun define(
        loader: InMemoryLoader,
        name: String,
        bytes: ByteArray,
    ): Class<*> {
        loader.defined[name.replace('/', '.')] = bytes
        return loader.loadClass(name.replace('/', '.'))
    }

    /**
     * Transforms [bytes] as the agent does in a JVM: the class-bytes capture sees them first, as it
     * does for every class, and the weave then reads them as the bytes received. A loader that serves
     * no class file leaves them the only source of the class's header.
     */
    private fun transformCaptured(
        loader: ClassLoader,
        name: String,
        bytes: ByteArray,
    ): ByteArray? {
        val captured = mutableListOf<ClassFileTransformer>()
        OtherlodeInstrumentation(
            AgentConfig.parse("includePackages=${AbsentTypeFixtures.PACKAGE}"),
            ProbeRegistry(),
            captureClassBytes = true,
        ).install(HotPathWeaver.capturing(ByteBuddyAgent.install(), captured))
        captured.first().transform(loader, name, null, null, bytes)
        return captured.last().transform(loader, name, null, null, bytes)
    }

    private fun <T> outcome(block: () -> T): String = runCatching { block().toString() }.getOrElse { "throws ${it.javaClass.name}" }

    @Test
    fun `a member class woven through a loader that serves no class files reflects as its unwoven twin does`() {
        val outerName = internal("Outer")
        val innerName = internal("Outer\$In")
        val entry = AbsentTypeFixtures.InnerEntry(innerName, outerName, "In", Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC)
        val outer = AbsentTypeFixtures.innerClassFixture(outerName, Opcodes.ACC_PUBLIC, listOf(entry))
        val inner = AbsentTypeFixtures.innerClassFixture(innerName, Opcodes.ACC_PUBLIC, listOf(entry))

        fun reflect(
            outerBytes: ByteArray,
            innerBytes: ByteArray,
        ): List<String> {
            val loader = memoryLoader()
            val outerType = define(loader, outerName, outerBytes)
            val innerType = define(loader, innerName, innerBytes)
            return listOf(
                outcome { outerType.declaredClasses.toList() },
                outcome { innerType.declaringClass },
                outcome { innerType.simpleName },
                outcome { innerType.isMemberClass },
                outcome { innerType.canonicalName },
            )
        }

        val unwoven = reflect(outer, inner)
        val wovenOuter = assertNotNull(transformCaptured(memoryLoader(), outerName, outer))
        val wovenInner = assertNotNull(transformCaptured(memoryLoader(), innerName, inner))

        assertEquals(unwoven, reflect(wovenOuter, wovenInner))
        assertTrue(unwoven.none { it.startsWith("throws") }, unwoven.toString())
    }

    @Test
    fun `a local class keeps the simple name its InnerClasses entry gives`() {
        val outerName = internal("Host")
        val localName = internal("Host\$Local\$1")
        val host = AbsentTypeFixtures.subtype(outerName, "java/lang/Object")
        val local =
            AbsentTypeFixtures.innerClassFixture(
                localName,
                Opcodes.ACC_FINAL or Opcodes.ACC_SUPER,
                listOf(AbsentTypeFixtures.InnerEntry(localName, null, "Local", 0)),
                enclosing = Triple(outerName, "<init>", "()V"),
            )

        fun simpleName(bytes: ByteArray): String {
            val loader = loaderOf(outerName to host)
            define(loader, outerName, host)
            return define(loader, localName, bytes).let { "${it.simpleName} local=${it.isLocalClass}" }
        }

        val woven = assertNotNull(transformCaptured(loaderOf(outerName to host, localName to local), localName, local))

        assertEquals("Local local=true", simpleName(local))
        assertEquals(simpleName(local), simpleName(woven))
    }

    @Test
    fun `a woven class keeps its final and deprecated flags`() {
        val name = internal("Flagged")
        val access = Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SUPER or Opcodes.ACC_DEPRECATED
        val original = AbsentTypeFixtures.innerClassFixture(name, access, emptyList())

        val woven = assertNotNull(transformer.transform(loaderOf(name to original), name, null, null, original))

        assertEquals(access, classAccessOf(woven))
    }

    @Test
    fun `a public class inheriting from a package-private class gains no visibility bridge`() {
        val parent = internal("Parent")
        val child = internal("Child")
        val parentBytes = AbsentTypeFixtures.packagePrivateParent(parent)
        val childBytes = AbsentTypeFixtures.subtype(child, parent)

        fun declaredMethods(bytes: ByteArray): String {
            val loader = loaderOf()
            define(loader, parent, parentBytes)
            return outcome { define(loader, child, bytes).declaredMethods.map { it.name }.filterNot { it.startsWith("\$otherlode") } }
        }

        val woven =
            assertNotNull(transformer.transform(loaderOf(parent to parentBytes, child to childBytes), child, null, null, childBytes))

        assertEquals("[]", declaredMethods(childBytes))
        assertEquals("[]", declaredMethods(woven))
    }

    @Test
    fun `an interface with nothing to probe keeps its class signature, an interface bound on an absent type included`() {
        val name = internal("Marker")
        val signature = "<T::L${AbsentTypeFixtures.ABSENT_PREFIX}Iface;>Ljava/lang/Object;"

        assertEquals(signature, wovenClassSignature(name, AbsentTypeFixtures.abstractInterface(name, signature)))
    }

    @Test
    fun `an interface whose superinterface is absent is refused and reported skipped, not woven`() {
        val name = internal("Extending")
        val original = AbsentTypeFixtures.interfaceExtending(name, AbsentTypeFixtures.ABSENT_PREFIX + "Contract")

        val failure = assertFailsWith<Throwable> { transformer.transform(loaderOf(name to original), name, null, null, original) }

        assertSkippedForSupertype(name, "com.example.absent.Contract", failure)
    }

    @Test
    fun `a class whose supertype its loader defined without serving a class file is refused with a reason that holds there`() {
        val parent = internal("MemoryParent")
        val child = internal("MemoryChild")
        val parentBytes = AbsentTypeFixtures.subtype(parent, "java/lang/Object")
        val childBytes = AbsentTypeFixtures.subtype(child, parent)
        val loader = memoryLoader()
        define(loader, parent, parentBytes)

        val failure = assertFailsWith<Throwable> { transformer.transform(loader, child, null, null, childBytes) }

        assertSkippedForSupertype(child, "com.example.withabsent.MemoryParent", failure)
    }

    private fun classAccessOf(bytes: ByteArray): Int {
        var access = 0
        ClassReader(bytes).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visit(
                    version: Int,
                    access0: Int,
                    name: String?,
                    signature: String?,
                    superName: String?,
                    interfaces: Array<out String>?,
                ) {
                    access = access0
                }
            },
            ClassReader.SKIP_CODE,
        )
        return access
    }
}
