package dev.otherlode.instrumentation

import dev.otherlode.ClassFileSupport
import dev.otherlode.benchmark.HotPathWeaver
import dev.otherlode.config.AgentConfig
import dev.otherlode.export.ProbeKind
import dev.otherlode.export.ResourceAttributes
import dev.otherlode.registry.ProbeRegistry
import net.bytebuddy.agent.ByteBuddyAgent
import net.bytebuddy.agent.builder.ResettableClassFileTransformer
import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.ClassWriter
import net.bytebuddy.jar.asm.Label
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The method tier decorates the received bytes and writes the members of a class below version 55
 * itself. These tests pin two shapes that describing the type for a rebase got wrong (a generic
 * value class, a method whose generic signature disagrees with its descriptor) and the members and
 * instructions of every form across the class-file versions where one differs.
 */
class DecoratedWeavingTest {
    private val resource = ResourceAttributes("test", null, "instance-1", null, "run-1")
    private var transformer: ResettableClassFileTransformer? = null
    private var installed: OtherlodeInstrumentation? = null

    @AfterTest
    fun tearDown() {
        transformer?.let { installed?.uninstall(ByteBuddyAgent.install(), it) }
        transformer = null
        installed = null
    }

    private fun install(
        registry: ProbeRegistry,
        includePackages: String,
    ) {
        val otherlode = OtherlodeInstrumentation(AgentConfig.parse("includePackages=$includePackages"), registry)
        installed = otherlode
        transformer = otherlode.install(ByteBuddyAgent.install())
    }

    private fun fixtureLoader() = FixtureClassLoader(arrayOf(File("build/classes/kotlin/test").toURI().toURL()), javaClass.classLoader)

    private fun hitsOf(
        registry: ProbeRegistry,
        className: String,
        methodName: String,
    ): List<Long> {
        val probes =
            registry
                .manifest(resource)
                .probes
                .filter { it.className == className && it.methodName == methodName && it.kind == ProbeKind.METHOD }
        val hits =
            registry
                .computeDeltaBatch(resource)
                .batch.deltas
                .associate { (it.classId to it.probeIndex) to it.hitsTotal }
        return probes.sortedBy { it.probeIndex }.map { hits[it.classId to it.probeIndex] ?: 0L }
    }

    @Test
    fun `a generic value class weaves and counts`() {
        val registry = ProbeRegistry()
        install(registry, "com.example.target.GenericBox")
        val loader = fixtureLoader()

        val user = Class.forName("com.example.target.GenericBoxUser", true, loader).getDeclaredConstructor().newInstance()
        val roundTrip = user.javaClass.getMethod("roundTrip", String::class.java)
        assertEquals("a", roundTrip.invoke(user, "a"))
        assertEquals("b", roundTrip.invoke(user, "b"))

        assertTrue("com.example.target.GenericBox" in registry.registeredClassNames(), "the value class was woven")
        assertTrue(registry.manifest(resource).skippedClasses.isEmpty(), "${registry.manifest(resource).skippedClasses}")
        assertEquals(listOf(2L), hitsOf(registry, "com.example.target.GenericBox", "unwrap-impl"))
        assertEquals(listOf(2L), hitsOf(registry, "com.example.target.GenericBox", "read-impl"))
    }

    @Test
    fun `a method whose generic signature disagrees with its descriptor gets its entry probe`() {
        val registry = ProbeRegistry()
        install(registry, "com.example.target.GroupingTarget")
        val loader = fixtureLoader()

        val target = Class.forName("com.example.target.GroupingTarget", true, loader).getDeclaredConstructor().newInstance()
        val counts = target.javaClass.getMethod("lengthCounts", List::class.java).invoke(target, listOf("a", "bb", "cc"))
        assertEquals(mapOf(1 to 1, 2 to 2), counts)

        val grouping = "com.example.target.GroupingTarget\$lengthCounts\$\$inlined\$groupingBy\$1"
        assertEquals(listOf(3L), hitsOf(registry, grouping, "keyOf"), "keyOf ran once per word")
    }

    /** Serves the fixture classes as resources, and defines the woven bytes put in [definitions]. */
    private class MatrixLoader(
        parent: ClassLoader,
        private val resources: Map<String, ByteArray>,
        val definitions: MutableMap<String, ByteArray> = mutableMapOf(),
    ) : ClassLoader(parent) {
        override fun findClass(name: String): Class<*> {
            val bytes = definitions[name.replace('.', '/')] ?: throw ClassNotFoundException(name)
            return defineClass(name, bytes, 0, bytes.size)
        }

        override fun getResourceAsStream(name: String): InputStream? =
            resources[name.removeSuffix(".class")]?.let { ByteArrayInputStream(it) } ?: super.getResourceAsStream(name)
    }

    private class Woven(
        val bytes: ByteArray,
        val loader: MatrixLoader,
        val registry: ProbeRegistry,
    )

    private fun weave(fixture: VersionedFixtures.Fixture): Woven {
        val registry = ProbeRegistry()
        val transformer = HotPathWeaver.offlineTransformer(listOf(VersionedFixtures.PACKAGE), registry)
        val loader = MatrixLoader(javaClass.classLoader, mapOf(fixture.internalName to fixture.bytes))
        val bytes =
            checkNotNull(transformer.transform(loader, fixture.internalName, null, null, fixture.bytes)) { "${fixture.name} was not woven" }
        loader.definitions[fixture.internalName] = bytes
        return Woven(bytes, loader, registry)
    }

    private class MethodFacts(
        val access: Int,
        val frames: Int,
        val callsForName: Boolean,
        val loadsClassConstant: Boolean,
    )

    private fun methodsOf(bytes: ByteArray): Map<String, MethodFacts> {
        val facts = HashMap<String, MethodFacts>()
        ClassReader(bytes).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor {
                    var frames = 0
                    var forName = false
                    var classConstant = false
                    return object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitFrame(
                            type: Int,
                            numLocal: Int,
                            local: Array<out Any>?,
                            numStack: Int,
                            stack: Array<out Any>?,
                        ) {
                            frames++
                        }

                        override fun visitMethodInsn(
                            opcode: Int,
                            owner: String,
                            name: String,
                            descriptor: String,
                            isInterface: Boolean,
                        ) {
                            if (owner == "java/lang/Class" && name == "forName") forName = true
                        }

                        override fun visitLdcInsn(value: Any?) {
                            if (value is net.bytebuddy.jar.asm.Type && value.sort == net.bytebuddy.jar.asm.Type.OBJECT) classConstant = true
                        }

                        override fun visitEnd() {
                            facts[name] = MethodFacts(access, frames, forName, classConstant)
                        }
                    }
                }
            },
            0,
        )
        return facts
    }

    private fun entries(
        registry: ProbeRegistry,
        className: String,
        methodName: String,
    ): Long = hitsOf(registry, className, methodName).sum()

    @Test
    fun `a class below version 55 with a type initializer gets the prelude in it, the accessors, and counts at every version`() {
        for (version in listOf(45, 48, 49, 50, 52)) {
            val fixture = VersionedFixtures.plain(version)
            val woven = weave(fixture)
            val label = "v$version"

            assertTrue(WovenBytes.declaresField(woven.bytes), "$label has the probe field")
            val methods = methodsOf(woven.bytes)
            val accessor = checkNotNull(methods[ProbeArrayForm.PROBE_ARRAY_ACCESSOR]) { "$label has the accessor" }
            val slowPath = checkNotNull(methods[ProbeArrayForm.PROBE_ARRAY_SLOW_PATH]) { "$label has the slow path" }
            val synthetic = Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC or Opcodes.ACC_SYNTHETIC
            assertEquals(synthetic, accessor.access, label)
            assertEquals(synthetic, slowPath.access, label)
            assertEquals(if (version >= 50) 1 else 0, accessor.frames, "$label: the accessor's frame is written from version 50")
            assertEquals(0, slowPath.frames, label)
            val initializer = checkNotNull(methods["<clinit>"]) { "$label has a type initializer" }
            assertEquals(version < 49, initializer.callsForName, "$label: the class literal is Class.forName below version 49")
            assertEquals(version >= 49, initializer.loadsClassConstant, label)

            val type = Class.forName(fixture.name, true, woven.loader)
            val target = type.getDeclaredConstructor().newInstance()
            assertEquals(7, type.getField("counter").get(null), "$label: the original initializer still ran")
            assertEquals(1, type.getMethod("pick", Int::class.java).invoke(target, 3))
            assertEquals(1L, entries(woven.registry, fixture.name, "<clinit>"), "$label: the initializer probe is counted by the prelude")
            assertEquals(1L, entries(woven.registry, fixture.name, "pick"), label)
        }
    }

    @Test
    fun `a class below version 55 without a type initializer gets a new one holding only the prelude`() {
        for (version in listOf(45, 48, 49, 50, 52)) {
            val fixture = VersionedFixtures.straight(version)
            val woven = weave(fixture)
            val label = "v$version"

            val methods = methodsOf(woven.bytes)
            assertTrue("<clinit>" in methods, "$label gained a type initializer")
            assertEquals(Opcodes.ACC_STATIC, methods.getValue("<clinit>").access, label)
            assertEquals(if (version >= 50) 1 else 0, methods.getValue(ProbeArrayForm.PROBE_ARRAY_ACCESSOR).frames, label)

            val type = Class.forName(fixture.name, true, woven.loader)
            assertEquals(7, type.getMethod("seven").invoke(null), label)
            assertEquals(1L, entries(woven.registry, fixture.name, "seven"), label)
        }
    }

    @Test
    fun `an interface at version 52 gets the accessors and an interface below it gets the field and prelude only`() {
        for (version in listOf(48, 49, 50, 51, 52)) {
            val fixture = VersionedFixtures.constants(version)
            val woven = weave(fixture)
            val label = "v$version"

            val methods = methodsOf(woven.bytes)
            assertTrue(WovenBytes.declaresField(woven.bytes), label)
            assertTrue("<clinit>" in methods, label)
            assertEquals(
                version >= ProbeArrayForm.INTERFACE_PRIVATE_METHOD_VERSION,
                ProbeArrayForm.PROBE_ARRAY_ACCESSOR in methods,
                label,
            )

            val type = Class.forName(fixture.name, true, woven.loader)
            assertEquals(9, type.getField("LIMIT").get(null), "$label: the original initializer still ran")
            assertEquals(1L, entries(woven.registry, fixture.name, "<clinit>"), label)
        }
    }

    @Test
    fun `a class from version 55 gets no field and no accessor, and its type initializer is counted by its entry probe`() {
        for (version in listOf(55, 61, 65).filter(ClassFileSupport::canDefine)) {
            val fixture = VersionedFixtures.plain(version)
            val woven = weave(fixture)

            assertFalse(WovenBytes.declaresField(woven.bytes), "v$version has no probe field")
            assertTrue(WovenBytes.loadsProbeConstant(woven.bytes))
            val methods = methodsOf(woven.bytes)
            assertFalse(ProbeArrayForm.PROBE_ARRAY_ACCESSOR in methods, "v$version")
            val type = Class.forName(fixture.name, true, woven.loader)
            type.getDeclaredConstructor().newInstance()
            assertEquals(1L, entries(woven.registry, fixture.name, "<clinit>"), "v$version: counted by its entry probe")
        }
    }

    /**
     * A class at version 52 whose type initializer loops back to its first instruction and catches
     * an exception, written with stack map frames as a compiler writes them: the prelude goes in
     * ahead of the loop's target and outside the handler's range, and every original frame still
     * holds.
     */
    private fun loopingInitializer(): VersionedFixtures.Fixture {
        val internalName = VersionedFixtures.internalName(52, "LoopingInit")
        val writer = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER, internalName, null, "java/lang/Object", null)
        writer.visitSource("LoopingInit.java", null)
        writer.visitField(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "total", "I", null, null).visitEnd()
        writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null).apply {
            val loop = Label()
            val start = Label()
            val end = Label()
            val handler = Label()
            val done = Label()
            visitCode()
            visitLabel(loop)
            visitLineNumber(1, loop)
            visitFieldInsn(Opcodes.GETSTATIC, internalName, "total", "I")
            visitInsn(Opcodes.ICONST_1)
            visitInsn(Opcodes.IADD)
            visitInsn(Opcodes.DUP)
            visitFieldInsn(Opcodes.PUTSTATIC, internalName, "total", "I")
            visitIntInsn(Opcodes.BIPUSH, 5)
            visitJumpInsn(Opcodes.IF_ICMPLT, loop)
            visitTryCatchBlock(start, end, handler, "java/lang/NumberFormatException")
            visitLabel(start)
            visitLdcInsn("not a number")
            visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "parseInt", "(Ljava/lang/String;)I", false)
            visitInsn(Opcodes.POP)
            visitLabel(end)
            visitJumpInsn(Opcodes.GOTO, done)
            visitLabel(handler)
            visitInsn(Opcodes.POP)
            visitFieldInsn(Opcodes.GETSTATIC, internalName, "total", "I")
            visitIntInsn(Opcodes.BIPUSH, 100)
            visitInsn(Opcodes.IADD)
            visitFieldInsn(Opcodes.PUTSTATIC, internalName, "total", "I")
            visitLabel(done)
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        writer.visitEnd()
        return VersionedFixtures.Fixture(internalName, writer.toByteArray())
    }

    @Test
    fun `a type initializer that loops back to its start and catches keeps its frames and behaviour after the prelude`() {
        val fixture = loopingInitializer()
        val woven = weave(fixture)

        val type = Class.forName(fixture.name, true, woven.loader)

        assertEquals(105, type.getField("total").get(null), "the loop ran five times and the handler once")
        assertTrue(methodsOf(woven.bytes).getValue("<clinit>").frames >= 2, "the original frames are kept")
        assertEquals(1L, entries(woven.registry, fixture.name, "<clinit>"), "the prelude counted the initializer once, not per loop")
    }

    @Test
    fun `an interface accessor calls its slow path as an interface method`() {
        val fixture = VersionedFixtures.constants(52)
        val woven = weave(fixture)
        val calls = mutableListOf<Boolean>()
        ClassReader(woven.bytes).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor? {
                    if (name != ProbeArrayForm.PROBE_ARRAY_ACCESSOR) return null
                    return object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitMethodInsn(
                            opcode: Int,
                            owner: String,
                            name: String,
                            descriptor: String,
                            isInterface: Boolean,
                        ) {
                            if (name == ProbeArrayForm.PROBE_ARRAY_SLOW_PATH) calls += isInterface
                        }
                    }
                }
            },
            0,
        )

        assertEquals(
            listOf(true),
            calls,
            "an invokestatic of an interface's own method must carry itf, or it throws IncompatibleClassChangeError",
        )
    }
}
