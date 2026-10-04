package dev.otherlode.instrumentation

import dev.otherlode.advice.MethodEntryAdvice
import dev.otherlode.benchmark.HotPathWeaver
import dev.otherlode.export.ProbeKind
import dev.otherlode.export.ResourceAttributes
import dev.otherlode.registry.ProbeRegistry
import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.ClassWriter
import net.bytebuddy.jar.asm.Label
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins which members each [ProbeArrayForm] adds to a woven class, and that each counts: a class of
 * version 55 or later keeps exactly its own members and loads a dynamic constant, a class below it
 * has the probe field, the `<clinit>` prelude and two private static synthetic accessors, and an
 * interface below version 52 has the field and prelude only. Classes are woven by the agent's real
 * transformer and defined from its output.
 */
class ProbeArrayFormTest {
    private companion object {
        const val TARGET = "com.example.target"
        const val GENERATED = "com.example.generated"
        val RESOURCE = ResourceAttributes("test", null, "instance-1", null, "run-1")
        val ACCESSORS = setOf(MethodEntryAdvice.PROBE_ARRAY_ACCESSOR, MethodEntryAdvice.PROBE_ARRAY_SLOW_PATH)
        const val ACC_PRIVATE_STATIC_SYNTHETIC = Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC or Opcodes.ACC_SYNTHETIC
    }

    private val registry = ProbeRegistry()
    private val transformer = HotPathWeaver.offlineTransformer(listOf(TARGET, GENERATED), registry)

    /** Serves and defines classes under [GENERATED] and [TARGET] from maps, so a class file and a woven class can differ. */
    private class InMemoryLoader(
        parent: ClassLoader,
        private val classFiles: Map<String, ByteArray>,
    ) : ClassLoader(parent) {
        val defined = HashMap<String, ByteArray>()

        override fun loadClass(
            name: String,
            resolve: Boolean,
        ): Class<*> {
            if (name !in classFiles) return super.loadClass(name, resolve)
            synchronized(getClassLoadingLock(name)) {
                val loaded =
                    findLoadedClass(name) ?: defined[name]!!.let { defineClass(name, it, 0, it.size) }
                if (resolve) resolveClass(loaded)
                return loaded
            }
        }

        override fun getResourceAsStream(name: String): InputStream? {
            val className = name.removeSuffix(".class").replace('/', '.')
            return classFiles[className]?.let { ByteArrayInputStream(it) } ?: super.getResourceAsStream(name)
        }
    }

    /** Weaves [classFile] of [className], and returns a loader that defines the woven class and the woven bytes. */
    private fun weave(
        className: String,
        classFile: ByteArray,
    ): Pair<InMemoryLoader, ByteArray> {
        val loader = InMemoryLoader(javaClass.classLoader, mapOf(className to classFile))
        val woven =
            assertNotNull(
                transformer.transform(loader, className.replace('.', '/'), null, null, classFile),
                "$className was woven",
            )
        loader.defined[className] = woven
        return loader to woven
    }

    private fun downgradedFixture(simpleName: String): ByteArray =
        File(LegacyFixtures.directory, "com/example/target/$simpleName.class").readBytes()

    private fun modernFixture(simpleName: String): ByteArray =
        File("build/classes/java/test/com/example/target/$simpleName.class").readBytes()

    /** The methods [bytes] declare, by name, with their access flags. */
    private fun methodFlags(bytes: ByteArray): Map<String, Int> {
        val flags = HashMap<String, Int>()
        ClassReader(bytes).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor? {
                    flags[name] = access
                    return null
                }
            },
            ClassReader.SKIP_CODE,
        )
        return flags
    }

    /** Whether the accessor's slow path in [bytes] reaches its own class through `Class.forName`. */
    private fun slowPathNamesClassByForName(bytes: ByteArray): Boolean {
        var found = false
        ClassReader(bytes).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor? =
                    if (name != MethodEntryAdvice.PROBE_ARRAY_SLOW_PATH) {
                        null
                    } else {
                        object : MethodVisitor(Opcodes.ASM9) {
                            override fun visitMethodInsn(
                                opcode: Int,
                                owner: String,
                                name: String,
                                descriptor: String,
                                isInterface: Boolean,
                            ) {
                                if (owner == "java/lang/Class" && name == "forName") found = true
                            }
                        }
                    }
            },
            ClassReader.SKIP_FRAMES,
        )
        return found
    }

    private fun hits(
        className: String,
        methodName: String,
        kind: ProbeKind = ProbeKind.METHOD,
    ): List<Long> {
        val probes =
            registry
                .manifest(RESOURCE)
                .probes
                .filter { it.className == className && it.methodName == methodName && it.kind == kind }
        val deltas =
            registry
                .computeDeltaBatch(RESOURCE)
                .batch.deltas
                .associate { (it.classId to it.probeIndex) to it.hitsTotal }
        return probes.sortedBy { it.probeIndex }.map { deltas[it.classId to it.probeIndex] ?: 0L }
    }

    @Test
    fun `a class of version 55 or later gets no field, no added method and no type initializer`() {
        val original = modernFixture("SampleTarget")
        assertTrue(WovenBytes.majorVersion(original) >= ProbeArrayForm.DYNAMIC_CONSTANT_VERSION)

        val (_, woven) = weave("$TARGET.SampleTarget", original)

        assertFalse(WovenBytes.declaresField(woven), "no probe field")
        assertTrue(WovenBytes.loadsProbeConstant(woven), "probes load the dynamic constant")
        assertEquals(methodFlags(original), methodFlags(woven), "the class declares the methods it did before, with the same flags")
        assertFalse("<clinit>" in methodFlags(woven), "no type initializer was added")
        assertEquals(WovenBytes.majorVersion(original), WovenBytes.majorVersion(woven))
    }

    @Test
    fun `a class of version 55 or later with its own type initializer keeps exactly its own members and counts it`() {
        val original = modernFixture("StaticInitTarget")

        val (loader, woven) = weave("$TARGET.StaticInitTarget", original)
        Class.forName("$TARGET.StaticInitTarget", true, loader)

        assertFalse(WovenBytes.declaresField(woven))
        assertEquals(methodFlags(original), methodFlags(woven))
        assertEquals(listOf(1L), hits("$TARGET.StaticInitTarget", "<clinit>"), "counted once, by an entry probe")
        assertEquals(listOf(1L), hits("$TARGET.StaticInitTarget", "poke"), "the call from its own initializer is counted")
    }

    @Test
    fun `a class below version 55 gets the field, the prelude and two private static synthetic accessors`() {
        val original = downgradedFixture("SampleTarget")
        assertEquals(LegacyFixtures.MAJOR_VERSION, WovenBytes.majorVersion(original))

        val (loader, woven) = weave("$TARGET.SampleTarget", original)

        assertTrue(WovenBytes.declaresField(woven), "the probe field")
        assertFalse(WovenBytes.loadsProbeConstant(woven), "no dynamic constant")
        val flags = methodFlags(woven)
        assertTrue("<clinit>" in flags, "the prelude's type initializer")
        for (accessor in ACCESSORS) {
            assertEquals(ACC_PRIVATE_STATIC_SYNTHETIC, flags[accessor], "$accessor is private static synthetic")
        }
        assertEquals(
            methodFlags(original).keys + ACCESSORS + "<clinit>",
            flags.keys,
            "nothing else was added",
        )

        val target = Class.forName("$TARGET.SampleTarget", true, loader).getDeclaredConstructor().newInstance()
        target.javaClass.getMethod("ping").invoke(target)
        assertEquals(listOf(1L), hits("$TARGET.SampleTarget", "ping"))
        assertEquals(listOf(0L), hits("$TARGET.SampleTarget", "neverCalled"))
    }

    @Test
    fun `the accessors are never probed and never listed in the manifest`() {
        val (loader, _) = weave("$TARGET.SampleTarget", downgradedFixture("SampleTarget"))
        Class.forName("$TARGET.SampleTarget", true, loader).getDeclaredConstructor().newInstance()

        val names = registry.manifest(RESOURCE).probes.map { it.methodName }
        assertTrue(names.none { it in ACCESSORS }, "$names")
        assertTrue("ping" in names)
    }

    @Test
    fun `a class below version 55 counts a call made from its own initializer through the accessor`() {
        val (loader, _) = weave("$TARGET.StaticInitTarget", downgradedFixture("StaticInitTarget"))
        Class.forName("$TARGET.StaticInitTarget", true, loader)

        assertEquals(listOf(1L), hits("$TARGET.StaticInitTarget", "<clinit>"), "the prelude counts it once")
        assertEquals(listOf(1L), hits("$TARGET.StaticInitTarget", "poke"))
    }

    @Test
    fun `an interface of version 52 gets the accessors and counts its static method`() {
        val original = downgradedFixture("DefaultMethodTarget")

        val (loader, woven) = weave("$TARGET.DefaultMethodTarget", original)

        val flags = methodFlags(woven)
        for (accessor in ACCESSORS) assertEquals(ACC_PRIVATE_STATIC_SYNTHETIC, flags[accessor], "$accessor on the interface")
        assertTrue(WovenBytes.declaresField(woven))
        val iface = Class.forName("$TARGET.DefaultMethodTarget", true, loader)
        assertEquals("static", iface.getMethod("staticThing").invoke(null))
        assertEquals(listOf(1L), hits("$TARGET.DefaultMethodTarget", "staticThing"))
        assertEquals(listOf(0L), hits("$TARGET.DefaultMethodTarget", "defaultThing"))
    }

    @Test
    fun `an interface below version 52 gets the field and the prelude and no accessor, and counts its type initializer`() {
        val name = "$GENERATED.Constants"
        val original = interfaceWithConstant(name, Opcodes.V1_7)

        val (loader, woven) = weave(name, original)
        Class.forName(name, true, loader)

        assertTrue(WovenBytes.declaresField(woven))
        assertEquals(setOf("<clinit>"), methodFlags(woven).keys, "no accessor on an interface that cannot have a private method")
        assertEquals(listOf(1L), hits(name, "<clinit>"))
    }

    @Test
    fun `a version 48 class weaves and counts entry and branch probes through its accessor`() {
        val name = "$GENERATED.Old"
        val original = oldClassWithBranch(name, Opcodes.V1_4)
        assertEquals(48, WovenBytes.majorVersion(original))

        val (loader, woven) = weave(name, original)

        assertEquals(48, WovenBytes.majorVersion(woven), "the class keeps its version")
        assertTrue(ACCESSORS.all { it in methodFlags(woven) })
        assertTrue(slowPathNamesClassByForName(woven), "below version 49 the slow path cannot use an ldc of a class")
        val instance = Class.forName(name, true, loader).getDeclaredConstructor().newInstance()
        val f = instance.javaClass.getMethod("f", Int::class.java)
        assertEquals(2, f.invoke(instance, 5))
        assertEquals(1, f.invoke(instance, 6))
        assertEquals(2, f.invoke(instance, -1))
        assertEquals(listOf(3L), hits(name, "f"))
        assertEquals(listOf(1L, 2L), hits(name, "f", ProbeKind.BRANCH).sorted())
    }

    @Test
    fun `a version 48 class with no branch weaves and counts through its accessor`() {
        val name = "$GENERATED.OldFlat"

        val (loader, woven) = weave(name, oldClassWithBranch(name, Opcodes.V1_4, withBranch = false))

        assertTrue(ACCESSORS.all { it in methodFlags(woven) })
        val instance = Class.forName(name, true, loader).getDeclaredConstructor().newInstance()
        assertEquals(2, instance.javaClass.getMethod("f", Int::class.java).invoke(instance, 5))
        assertEquals(listOf(1L), hits(name, "f"))
    }

    /** `interface Constants { Object X = new Object(); }`, the one shape of interface below version 52 with code to probe. */
    private fun interfaceWithConstant(
        name: String,
        version: Int,
    ): ByteArray {
        val internal = name.replace('.', '/')
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(version, Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT, internal, null, "java/lang/Object", null)
        writer.visitField(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL, "X", "Ljava/lang/Object;", null, null).visitEnd()
        val clinit = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null)
        clinit.visitCode()
        clinit.visitTypeInsn(Opcodes.NEW, "java/lang/Object")
        clinit.visitInsn(Opcodes.DUP)
        clinit.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, internal, "X", "Ljava/lang/Object;")
        clinit.visitInsn(Opcodes.RETURN)
        clinit.visitMaxs(0, 0)
        clinit.visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }

    /** `public class Old { public int f(int x) { return x > 5 ? 1 : 2; } }` with no stack map frames, as a class of that version has. */
    private fun oldClassWithBranch(
        name: String,
        version: Int,
        withBranch: Boolean = true,
    ): ByteArray {
        val internal = name.replace('.', '/')
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(version, Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER, internal, null, "java/lang/Object", null)
        val init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
        init.visitCode()
        init.visitVarInsn(Opcodes.ALOAD, 0)
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
        init.visitInsn(Opcodes.RETURN)
        init.visitMaxs(0, 0)
        init.visitEnd()
        val method = writer.visitMethod(Opcodes.ACC_PUBLIC, "f", "(I)I", null, null)
        method.visitCode()
        if (withBranch) {
            val other = Label()
            method.visitVarInsn(Opcodes.ILOAD, 1)
            method.visitIntInsn(Opcodes.BIPUSH, 5)
            method.visitJumpInsn(Opcodes.IF_ICMPLE, other)
            method.visitInsn(Opcodes.ICONST_1)
            method.visitInsn(Opcodes.IRETURN)
            method.visitLabel(other)
        }
        method.visitInsn(Opcodes.ICONST_2)
        method.visitInsn(Opcodes.IRETURN)
        method.visitMaxs(0, 0)
        method.visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }
}
