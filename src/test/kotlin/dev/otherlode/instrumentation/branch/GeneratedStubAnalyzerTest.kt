package dev.otherlode.instrumentation.branch

import dev.otherlode.export.GeneratedBy
import net.bytebuddy.jar.asm.ClassWriter
import net.bytebuddy.jar.asm.Handle
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import net.bytebuddy.jar.asm.Type
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Hand-built classes pinning the edges of two generated-method rules: the `-jvm-default=disable`
 * stub in an implementing class and a record's `equals`, `hashCode` and `toString`. The compilers'
 * own output is covered by `KotlincMatrixTest` and `JavacMatrixTest`.
 */
class GeneratedStubAnalyzerTest {
    private companion object {
        const val IFACE = "com/example/Iface"
        const val IMPL = "com/example/Impl"
        const val STUB_DESCRIPTOR = "(Ljava/lang/String;)Ljava/lang/String;"
        const val FORWARDED_DESCRIPTOR = "(L$IFACE;Ljava/lang/String;)Ljava/lang/String;"
        const val RECORD = "com/example/Rec"
        const val OBJECT_METHODS = "java/lang/runtime/ObjectMethods"
        const val BOOTSTRAP_DESCRIPTOR =
            "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/invoke/TypeDescriptor;" +
                "Ljava/lang/Class;Ljava/lang/String;[Ljava/lang/invoke/MethodHandle;)Ljava/lang/Object;"
    }

    /**
     * A class `Impl` implementing [interfaces], with a Kotlin `Metadata` annotation when
     * [kotlin] is set and one `public String m(String)` whose body [body] writes.
     */
    private fun implClass(
        interfaces: Array<String> = arrayOf(IFACE),
        kotlin: Boolean = true,
        body: (MethodVisitor) -> Unit,
    ): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, IMPL, null, "java/lang/Object", interfaces)
        if (kotlin) writer.visitAnnotation("Lkotlin/Metadata;", true).visitEnd()
        val mv = writer.visitMethod(Opcodes.ACC_PUBLIC, "m", STUB_DESCRIPTOR, null, null)
        mv.visitCode()
        body(mv)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun forwardTo(
        owner: String,
        extra: (MethodVisitor) -> Unit = {},
    ): (MethodVisitor) -> Unit =
        { mv ->
            mv.visitVarInsn(Opcodes.ALOAD, 0)
            mv.visitVarInsn(Opcodes.ALOAD, 1)
            extra(mv)
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "m", FORWARDED_DESCRIPTOR, false)
            mv.visitInsn(Opcodes.ARETURN)
        }

    /**
     * A Kotlin class implementing the interface with one `public String n(int, String)` whose body
     * loads `this`, the `int` boxed by [box] (null for none), the `String` boxed by [boxReference]
     * (null for none), then calls the interface's `DefaultImpls.n` with [forwarded] and returns.
     */
    private fun boxingStub(
        forwarded: String,
        box: Pair<String, String>? = "java/lang/Integer" to "(I)Ljava/lang/Integer;",
        boxReference: Pair<String, String>? = null,
        boxTwice: Boolean = false,
    ): GeneratedBy {
        val descriptor = "(ILjava/lang/String;)Ljava/lang/String;"
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, IMPL, null, "java/lang/Object", arrayOf(IFACE))
        writer.visitAnnotation("Lkotlin/Metadata;", true).visitEnd()
        val mv = writer.visitMethod(Opcodes.ACC_PUBLIC, "n", descriptor, null, null)
        mv.visitCode()
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitVarInsn(Opcodes.ILOAD, 1)
        box?.let { (owner, boxDescriptor) ->
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "valueOf", boxDescriptor, false)
            if (boxTwice) mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "valueOf", boxDescriptor, false)
        }
        mv.visitVarInsn(Opcodes.ALOAD, 2)
        boxReference?.let { (owner, boxDescriptor) -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "valueOf", boxDescriptor, false) }
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "$IFACE\$DefaultImpls", "n", forwarded, false)
        mv.visitInsn(Opcodes.ARETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
        writer.visitEnd()
        return BranchSiteAnalyzer.analyze(writer.toByteArray()) { _, _ -> true }.generatedBy("n", descriptor)
    }

    @Test
    fun `a stub that boxes an int with Integer valueOf for an Object parameter is DEFAULT_IMPLS`() {
        assertEquals(GeneratedBy.DEFAULT_IMPLS, boxingStub("(L$IFACE;Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/String;"))
    }

    @Test
    fun `a stub that boxes with the wrong box type, boxes twice, or boxes a reference is NONE`() {
        val forwarded = "(L$IFACE;Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/String;"

        assertEquals(GeneratedBy.NONE, boxingStub(forwarded, box = "java/lang/Character" to "(C)Ljava/lang/Character;"))
        assertEquals(GeneratedBy.NONE, boxingStub(forwarded, boxTwice = true))
        assertEquals(
            GeneratedBy.NONE,
            boxingStub(
                "(L$IFACE;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/String;",
                boxReference = "java/lang/String" to "(Ljava/lang/Object;)Ljava/lang/String;",
            ),
        )
    }

    @Test
    fun `a stub that passes an unboxed int where DefaultImpls takes Object, or boxes where it takes int, is NONE`() {
        assertEquals(GeneratedBy.NONE, boxingStub("(L$IFACE;Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/String;", box = null))
        assertEquals(GeneratedBy.NONE, boxingStub("(L$IFACE;ILjava/lang/String;)Ljava/lang/String;"))
    }

    private fun markOfStub(bytes: ByteArray): GeneratedBy =
        BranchSiteAnalyzer.analyze(bytes) { _, _ -> true }.generatedBy("m", STUB_DESCRIPTOR)

    @Test
    fun `a Kotlin class method that loads this and its parameter and calls its interface's DefaultImpls is DEFAULT_IMPLS`() {
        assertEquals(GeneratedBy.DEFAULT_IMPLS, markOfStub(implClass(body = forwardTo("$IFACE\$DefaultImpls"))))
    }

    @Test
    fun `the same body in a class without Kotlin metadata is NONE`() {
        assertEquals(GeneratedBy.NONE, markOfStub(implClass(kotlin = false, body = forwardTo("$IFACE\$DefaultImpls"))))
    }

    @Test
    fun `a DefaultImpls owner that is not one of the class's interfaces is NONE`() {
        assertEquals(GeneratedBy.NONE, markOfStub(implClass(body = forwardTo("com/example/Other\$DefaultImpls"))))
        assertEquals(GeneratedBy.NONE, markOfStub(implClass(interfaces = emptyArray(), body = forwardTo("$IFACE\$DefaultImpls"))))
    }

    @Test
    fun `an owner that is the interface itself and not its DefaultImpls is NONE`() {
        assertEquals(GeneratedBy.NONE, markOfStub(implClass(body = forwardTo(IFACE))))
    }

    @Test
    fun `a stub with one extra instruction is NONE`() {
        val extra = forwardTo("$IFACE\$DefaultImpls") { it.visitInsn(Opcodes.NOP) }
        assertEquals(GeneratedBy.NONE, markOfStub(implClass(body = extra)))
    }

    @Test
    fun `a stub that skips a parameter or calls another name is NONE`() {
        val skipsParameter: (MethodVisitor) -> Unit = { mv ->
            mv.visitVarInsn(Opcodes.ALOAD, 0)
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "$IFACE\$DefaultImpls", "m", "(L$IFACE;)Ljava/lang/String;", false)
            mv.visitInsn(Opcodes.ARETURN)
        }
        val otherName: (MethodVisitor) -> Unit = { mv ->
            mv.visitVarInsn(Opcodes.ALOAD, 0)
            mv.visitVarInsn(Opcodes.ALOAD, 1)
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "$IFACE\$DefaultImpls", "other", FORWARDED_DESCRIPTOR, false)
            mv.visitInsn(Opcodes.ARETURN)
        }

        assertEquals(GeneratedBy.NONE, markOfStub(implClass(body = skipsParameter)))
        assertEquals(GeneratedBy.NONE, markOfStub(implClass(body = otherName)))
    }

    /** A body calling the interface's `DefaultImpls.m` with [forwarded], then [afterCall], then `areturn`. */
    private fun forwardWith(
        forwarded: String,
        afterCall: (MethodVisitor) -> Unit = {},
    ): (MethodVisitor) -> Unit =
        { mv ->
            mv.visitVarInsn(Opcodes.ALOAD, 0)
            mv.visitVarInsn(Opcodes.ALOAD, 1)
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "$IFACE\$DefaultImpls", "m", forwarded, false)
            afterCall(mv)
            mv.visitInsn(Opcodes.ARETURN)
        }

    @Test
    fun `a generic interface's stub, calling with Object and casting the Object it gets back to its own return type, is DEFAULT_IMPLS`() {
        val erased =
            forwardWith("(L$IFACE;Ljava/lang/Object;)Ljava/lang/Object;") { it.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/String") }
        assertEquals(GeneratedBy.DEFAULT_IMPLS, markOfStub(implClass(body = erased)))
    }

    @Test
    fun `a stub whose call returns another reference type with no cast, or a cast to another type, is NONE`() {
        val noCast = forwardWith("(L$IFACE;Ljava/lang/String;)Ljava/lang/Object;")
        val wrongCast =
            forwardWith("(L$IFACE;Ljava/lang/String;)Ljava/lang/Object;") { it.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/CharSequence") }
        val needlessCast = forwardWith(FORWARDED_DESCRIPTOR) { it.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/String") }

        assertEquals(GeneratedBy.NONE, markOfStub(implClass(body = noCast)))
        assertEquals(GeneratedBy.NONE, markOfStub(implClass(body = wrongCast)))
        assertEquals(GeneratedBy.NONE, markOfStub(implClass(body = needlessCast)))
    }

    @Test
    fun `a stub whose called parameter is a primitive where its own is a reference is NONE`() {
        assertEquals(GeneratedBy.NONE, markOfStub(implClass(body = forwardWith("(L$IFACE;I)Ljava/lang/String;"))))
    }

    @Test
    fun `a stub that boxes the primitive a DefaultImpls method returns is NONE, since only a hand-written super call writes that`() {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, IMPL, null, "java/lang/Object", arrayOf(IFACE))
        writer.visitAnnotation("Lkotlin/Metadata;", true).visitEnd()
        val mv = writer.visitMethod(Opcodes.ACC_PUBLIC, "q", "()Ljava/lang/Integer;", null, null)
        mv.visitCode()
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "$IFACE\$DefaultImpls", "q", "(L$IFACE;)I", false)
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false)
        mv.visitInsn(Opcodes.ARETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
        writer.visitEnd()

        assertEquals(
            GeneratedBy.NONE,
            BranchSiteAnalyzer.analyze(writer.toByteArray()) { _, _ -> true }.generatedBy("q", "()Ljava/lang/Integer;"),
        )
    }

    private fun recordClass(
        equalsBody: (MethodVisitor) -> Unit,
        bootstrapOwner: String = OBJECT_METHODS,
    ): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, RECORD, null, "java/lang/Record", null)
        val bootstrap = Handle(Opcodes.H_INVOKESTATIC, bootstrapOwner, "bootstrap", BOOTSTRAP_DESCRIPTOR, false)

        fun method(
            name: String,
            descriptor: String,
            body: (MethodVisitor) -> Unit,
        ) {
            val mv = writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, name, descriptor, null, null)
            mv.visitCode()
            body(mv)
            mv.visitMaxs(0, 0)
            mv.visitEnd()
        }
        method("toString", "()Ljava/lang/String;") { mv ->
            mv.visitVarInsn(Opcodes.ALOAD, 0)
            mv.visitInvokeDynamicInsn("toString", "(L$RECORD;)Ljava/lang/String;", bootstrap, Type.getObjectType(RECORD), "")
            mv.visitInsn(Opcodes.ARETURN)
        }
        method("hashCode", "()I") { mv ->
            mv.visitVarInsn(Opcodes.ALOAD, 0)
            mv.visitInvokeDynamicInsn("hashCode", "(L$RECORD;)I", bootstrap, Type.getObjectType(RECORD), "")
            mv.visitInsn(Opcodes.IRETURN)
        }
        method("equals", "(Ljava/lang/Object;)Z") { mv ->
            equalsBody(mv)
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun javacEquals(
        before: (MethodVisitor) -> Unit = {},
        bootstrapOwner: String = OBJECT_METHODS,
    ): (MethodVisitor) -> Unit =
        { mv ->
            val bootstrap = Handle(Opcodes.H_INVOKESTATIC, bootstrapOwner, "bootstrap", BOOTSTRAP_DESCRIPTOR, false)
            mv.visitVarInsn(Opcodes.ALOAD, 0)
            mv.visitVarInsn(Opcodes.ALOAD, 1)
            before(mv)
            mv.visitInvokeDynamicInsn("equals", "(L$RECORD;Ljava/lang/Object;)Z", bootstrap, Type.getObjectType(RECORD), "")
            mv.visitInsn(Opcodes.IRETURN)
        }

    private fun recordMarks(bytes: ByteArray): Triple<GeneratedBy, GeneratedBy, GeneratedBy> {
        val analysis = BranchSiteAnalyzer.analyze(bytes) { _, _ -> true }
        return Triple(
            analysis.generatedBy("equals", "(Ljava/lang/Object;)Z"),
            analysis.generatedBy("hashCode", "()I"),
            analysis.generatedBy("toString", "()Ljava/lang/String;"),
        )
    }

    @Test
    fun `a record's methods that are exactly javac's ObjectMethods indy are RECORD`() {
        assertEquals(Triple(GeneratedBy.RECORD, GeneratedBy.RECORD, GeneratedBy.RECORD), recordMarks(recordClass(javacEquals())))
    }

    @Test
    fun `a record equals with one extra instruction is NONE and the other two stay RECORD`() {
        val extra = javacEquals(before = { it.visitInsn(Opcodes.NOP) })

        assertEquals(Triple(GeneratedBy.NONE, GeneratedBy.RECORD, GeneratedBy.RECORD), recordMarks(recordClass(extra)))
    }

    @Test
    fun `a record equals through another bootstrap method is NONE`() {
        val other = javacEquals(bootstrapOwner = "com/example/OtherMethods")

        assertEquals(GeneratedBy.NONE, recordMarks(recordClass(other, bootstrapOwner = OBJECT_METHODS)).first)
    }
}
