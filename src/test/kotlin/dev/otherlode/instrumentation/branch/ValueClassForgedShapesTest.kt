package dev.otherlode.instrumentation.branch

import dev.otherlode.export.GeneratedBy
import dev.otherlode.instrumentation.CompilerFixtures
import net.bytebuddy.jar.asm.ClassWriter
import net.bytebuddy.jar.asm.Label
import net.bytebuddy.jar.asm.Opcodes
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Shapes no compiler in the matrix writes, built with ASM so each rule's edge is pinned: a value
 * class whose `box-impl` and `unbox-impl` are not synthetic, a stub name whose hash is one
 * character short or long, a stub that boxes into a class that is not a value class, and the
 * suspend tail through the interface's own accessor that `all-compatibility` writes.
 */
class ValueClassForgedShapesTest {
    private val build =
        CompilerFixtures.kotlinc(CompilerFixtures.kotlincVersions.first { !CompilerFixtures.kotlincDefaultsToJvmDefaultEnable(it) })

    private val pkg = "com/example/target/kotlinc"
    private val id = "$pkg/Id"
    private val store = "$pkg/ValueStore"

    private fun ClassWriter.kotlinClass(
        name: String,
        access: Int = Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER,
        signature: String? = null,
        interfaces: Array<String>? = null,
    ) {
        visit(Opcodes.V1_8, access, name, signature, "java/lang/Object", interfaces)
        visitAnnotation("Lkotlin/Metadata;", true).visitEnd()
    }

    private fun marksOf(
        bytes: ByteArray,
        extra: Map<String, ByteArray> = emptyMap(),
    ): BranchSiteAnalyzer.Analysis =
        BranchSiteAnalyzer.analyze(
            bytes,
            { name -> extra[name] ?: build.lookup(name) },
            includePackages = listOf("com.example.target"),
        ) { _, _ -> true }

    /** A value class shape with [boxAccess] on `box-impl` and [unboxAccess] on `unbox-impl`. */
    private fun valueClass(
        name: String,
        boxAccess: Int,
        unboxAccess: Int,
        interfaces: Array<String>? = null,
        extra: (ClassWriter) -> Unit = {},
    ): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.kotlinClass(name, Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SUPER, interfaces = interfaces)
        writer.visitField(Opcodes.ACC_PRIVATE or Opcodes.ACC_FINAL, "v", "I", null, null).visitEnd()
        writer.visitMethod(boxAccess, "box-impl", "(I)L$name;", null, null).apply {
            visitCode()
            visitInsn(Opcodes.ACONST_NULL)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        writer.visitMethod(unboxAccess, "unbox-impl", "()I", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitFieldInsn(Opcodes.GETFIELD, name, "v", "I")
            visitInsn(Opcodes.IRETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "toString-impl", "(I)Ljava/lang/String;", null, null).apply {
            visitCode()
            visitInsn(Opcodes.ACONST_NULL)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        extra(writer)
        writer.visitEnd()
        return writer.toByteArray()
    }

    private val syntheticStatic = Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL or Opcodes.ACC_SYNTHETIC
    private val syntheticInstance = Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SYNTHETIC
    private val greets = "$pkg/Greets"

    /** A `greet()` that casts `this` to [castTo] and calls `Greets$DefaultImpls.greet`, as kotlinc 2.1's stub in a value class does. */
    private fun castingStub(castTo: String): (ClassWriter) -> Unit =
        { writer ->
            writer.visitMethod(Opcodes.ACC_PUBLIC, "greet", "()Ljava/lang/String;", null, null).apply {
                visitCode()
                visitVarInsn(Opcodes.ALOAD, 0)
                visitTypeInsn(Opcodes.CHECKCAST, castTo)
                visitMethodInsn(Opcodes.INVOKESTATIC, "$greets\$DefaultImpls", "greet", "(L$greets;)Ljava/lang/String;", false)
                visitInsn(Opcodes.ARETURN)
                visitMaxs(0, 0)
                visitEnd()
            }
        }

    @Test
    fun `a stub that casts this to its interface is DEFAULT_IMPLS only in a value class`() {
        val inValueClass =
            marksOf(valueClass("$pkg/ForgedGreetValue", syntheticStatic, syntheticInstance, arrayOf(greets), castingStub(greets)))
        val wrongCast =
            marksOf(
                valueClass(
                    "$pkg/ForgedGreetWrongCast",
                    syntheticStatic,
                    syntheticInstance,
                    arrayOf(greets),
                    castingStub("java/lang/Object"),
                ),
            )
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.kotlinClass("$pkg/ForgedGreetPlain", interfaces = arrayOf(greets))
        castingStub(greets)(writer)
        writer.visitEnd()
        val inPlainClass = marksOf(writer.toByteArray())

        assertEquals(GeneratedBy.DEFAULT_IMPLS, inValueClass.generatedBy("greet", "()Ljava/lang/String;"))
        assertEquals(GeneratedBy.NONE, wrongCast.generatedBy("greet", "()Ljava/lang/String;"))
        assertEquals(GeneratedBy.NONE, inPlainClass.generatedBy("greet", "()Ljava/lang/String;"))
    }

    @Test
    fun `a value class's static that calls an inherited default is unmarked when the class's own forwarder calls it`() {
        val name = "$pkg/ForgedOwnGreet"

        fun inheritedStatic(writer: ClassWriter) {
            writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "greet-impl", "(I)Ljava/lang/String;", null, null).apply {
                visitCode()
                visitVarInsn(Opcodes.ILOAD, 0)
                visitMethodInsn(Opcodes.INVOKESTATIC, name, "box-impl", "(I)L$name;", false)
                visitMethodInsn(Opcodes.INVOKEVIRTUAL, name, "greet", "()Ljava/lang/String;", false)
                visitInsn(Opcodes.ARETURN)
                visitMaxs(0, 0)
                visitEnd()
            }
        }

        fun ownForwarder(writer: ClassWriter) {
            writer.visitMethod(Opcodes.ACC_PUBLIC, "greet", "()Ljava/lang/String;", null, null).apply {
                visitCode()
                visitVarInsn(Opcodes.ALOAD, 0)
                visitFieldInsn(Opcodes.GETFIELD, name, "v", "I")
                visitMethodInsn(Opcodes.INVOKESTATIC, name, "greet-impl", "(I)Ljava/lang/String;", false)
                visitInsn(Opcodes.ARETURN)
                visitMaxs(0, 0)
                visitEnd()
            }
        }

        val inherited = marksOf(valueClass(name, syntheticStatic, syntheticInstance, arrayOf(greets)) { inheritedStatic(it) })
        val own =
            marksOf(
                valueClass(name, syntheticStatic, syntheticInstance, arrayOf(greets)) {
                    inheritedStatic(it)
                    ownForwarder(it)
                },
            )

        assertEquals(GeneratedBy.VALUE_CLASS, inherited.generatedBy("greet-impl", "(I)Ljava/lang/String;"))
        assertEquals(GeneratedBy.NONE, own.generatedBy("greet-impl", "(I)Ljava/lang/String;"))
    }

    @Test
    fun `a class whose box-impl and unbox-impl are not synthetic is no value class`() {
        val synthetic = Opcodes.ACC_SYNTHETIC
        val static = Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL
        val instance = Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL

        val real = marksOf(valueClass("$pkg/ForgedValue", static or synthetic, instance or synthetic))
        val plainBox = marksOf(valueClass("$pkg/ForgedPlainBox", static, instance or synthetic))
        val plainUnbox = marksOf(valueClass("$pkg/ForgedPlainUnbox", static or synthetic, instance))

        assertEquals(GeneratedBy.VALUE_CLASS, real.generatedBy("toString-impl", "(I)Ljava/lang/String;"))
        assertEquals(GeneratedBy.NONE, plainBox.generatedBy("toString-impl", "(I)Ljava/lang/String;"))
        assertEquals(GeneratedBy.NONE, plainUnbox.generatedBy("toString-impl", "(I)Ljava/lang/String;"))
    }

    /**
     * A class implementing `ValueStore<`[boxedInto]`>` with one stub named [name] that boxes its
     * argument into [boxedInto], calls `ValueStore$DefaultImpls.put` and unboxes the result.
     */
    private fun stub(
        className: String,
        name: String,
        boxedInto: String,
    ): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.kotlinClass(className, signature = "Ljava/lang/Object;L$store<L$boxedInto;>;", interfaces = arrayOf(store))
        writer.visitMethod(Opcodes.ACC_PUBLIC, name, "(I)I", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitVarInsn(Opcodes.ILOAD, 1)
            visitMethodInsn(Opcodes.INVOKESTATIC, boxedInto, "box-impl", "(I)L$boxedInto;", false)
            visitMethodInsn(Opcodes.INVOKESTATIC, "$store\$DefaultImpls", "put", "(L$store;Ljava/lang/Object;)Ljava/lang/Object;", false)
            visitTypeInsn(Opcodes.CHECKCAST, boxedInto)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, boxedInto, "unbox-impl", "()I", false)
            visitInsn(Opcodes.IRETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    @Test
    fun `a stub's name takes exactly seven hash characters`() {
        assertEquals(GeneratedBy.DEFAULT_IMPLS, marksOf(stub("$pkg/ForgedStub7", "put-abcdefg", id)).generatedBy("put-abcdefg", "(I)I"))
        assertEquals(GeneratedBy.NONE, marksOf(stub("$pkg/ForgedStub6", "put-abcdef", id)).generatedBy("put-abcdef", "(I)I"))
        assertEquals(GeneratedBy.NONE, marksOf(stub("$pkg/ForgedStub8", "put-abcdefgh", id)).generatedBy("put-abcdefgh", "(I)I"))
    }

    @Test
    fun `a stub that boxes into a class that is not a value class is unmarked`() {
        val fake = "$pkg/ForgedNotValue"
        val static = Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL
        val notValue = valueClass(fake, static, Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL)

        val marks = marksOf(stub("$pkg/ForgedStubFake", "put-abcdefg", fake), mapOf(fake to notValue))

        assertEquals(GeneratedBy.NONE, marks.generatedBy("put-abcdefg", "(I)I"))
    }

    @Test
    fun `a forwarder through the interface's own accessor that ends with the suspend tail is DEFAULT_IMPLS`() {
        val iface = "$pkg/ForgedSuspend"
        val continuation = "Lkotlin/coroutines/Continuation;"
        val descriptor = "(L$iface;$continuation)Ljava/lang/Object;"

        fun forwarder(
            withTail: Boolean,
            dropsBeforeUnit: Boolean = true,
        ): ByteArray {
            val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
            writer.kotlinClass("$iface\$DefaultImpls", Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SUPER)
            writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "run", descriptor, null, null).apply {
                visitCode()
                visitVarInsn(Opcodes.ALOAD, 0)
                visitVarInsn(Opcodes.ALOAD, 1)
                visitMethodInsn(Opcodes.INVOKESTATIC, iface, "access\$run\$jd", descriptor, true)
                if (withTail) {
                    val notSuspended = Label()
                    visitInsn(Opcodes.DUP)
                    visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        "kotlin/coroutines/intrinsics/IntrinsicsKt",
                        "getCOROUTINE_SUSPENDED",
                        "()Ljava/lang/Object;",
                        false,
                    )
                    visitJumpInsn(Opcodes.IF_ACMPNE, notSuspended)
                    visitInsn(Opcodes.ARETURN)
                    visitLabel(notSuspended)
                    if (dropsBeforeUnit) visitInsn(Opcodes.POP)
                    visitFieldInsn(Opcodes.GETSTATIC, "kotlin/Unit", "INSTANCE", "Lkotlin/Unit;")
                }
                visitInsn(Opcodes.ARETURN)
                visitMaxs(0, 0)
                visitEnd()
            }
            writer.visitEnd()
            return writer.toByteArray()
        }

        assertEquals(GeneratedBy.DEFAULT_IMPLS, marksOf(forwarder(withTail = true)).generatedBy("run", descriptor))
        assertEquals(GeneratedBy.DEFAULT_IMPLS, marksOf(forwarder(withTail = false)).generatedBy("run", descriptor))
        assertEquals(GeneratedBy.NONE, marksOf(forwarder(withTail = true, dropsBeforeUnit = false)).generatedBy("run", descriptor))
    }
}
