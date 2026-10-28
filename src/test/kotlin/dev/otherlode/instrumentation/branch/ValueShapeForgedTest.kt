package dev.otherlode.instrumentation.branch

import dev.otherlode.export.GeneratedBy
import dev.otherlode.instrumentation.CompilerFixtures
import net.bytebuddy.jar.asm.ClassWriter
import net.bytebuddy.jar.asm.Label
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Edges of the value-class static, argument cast and null-guarded box rules that no compiler in the
 * matrix writes, built with ASM over the matrix fixtures' interfaces: a static that is final or
 * names another member, a cast to a type the call does not take, a second conversion at one
 * position, a null guard missing a step, and a guard over a primitive.
 */
class ValueShapeForgedTest {
    private val build =
        CompilerFixtures.kotlinc(CompilerFixtures.kotlincVersions.first { !CompilerFixtures.kotlincDefaultsToJvmDefaultEnable(it) })

    private val pkg = "com/example/target/kotlinc"
    private val label = "$pkg/Label"
    private val id = "$pkg/Id"
    private val string = "java/lang/String"
    private val publicStatic = Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC
    private val intrinsics = "kotlin/jvm/internal/Intrinsics"

    private fun marksOf(bytes: ByteArray): BranchSiteAnalyzer.Analysis =
        BranchSiteAnalyzer.analyze(bytes, build.lookup, includePackages = listOf("com.example.target")) { _, _ -> true }

    private fun MethodVisitor.body(code: MethodVisitor.() -> Unit) {
        visitCode()
        code()
        visitMaxs(0, 0)
        visitEnd()
    }

    private fun valueClass(
        name: String,
        interfaceName: String,
        signature: String,
        statics: ClassWriter.() -> Unit,
    ): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SUPER,
            name,
            signature,
            "java/lang/Object",
            arrayOf(interfaceName),
        )
        writer.visitAnnotation("Lkotlin/Metadata;", true).visitEnd()
        writer.visitField(Opcodes.ACC_PRIVATE or Opcodes.ACC_FINAL, "v", "I", null, null).visitEnd()
        val synthetic = Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SYNTHETIC
        writer.visitMethod(synthetic or Opcodes.ACC_STATIC, "box-impl", "(I)L$name;", null, null).body {
            visitInsn(Opcodes.ACONST_NULL)
            visitInsn(Opcodes.ARETURN)
        }
        writer.visitMethod(synthetic, "unbox-impl", "()I", null, null).body {
            visitInsn(Opcodes.ICONST_0)
            visitInsn(Opcodes.IRETURN)
        }
        writer.statics()
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun MethodVisitor.nullCheck(slot: Int) {
        visitVarInsn(Opcodes.ALOAD, slot)
        visitLdcInsn("t")
        visitMethodInsn(Opcodes.INVOKESTATIC, intrinsics, "checkNotNullParameter", "(Ljava/lang/Object;Ljava/lang/String;)V", false)
    }

    private val echo = "$pkg/Echoing"
    private val echoSignature = "Ljava/lang/Object;L$echo<Ljava/lang/String;>;"
    private val echoDescriptor = "(ILjava/lang/String;)Ljava/lang/String;"

    /** A value class over `Int` implementing `Echoing<String>` with one static built by [code]. */
    private fun echoValue(
        name: String = "echo-impl",
        descriptor: String = echoDescriptor,
        access: Int = publicStatic,
        code: MethodVisitor.(self: String) -> Unit,
    ): BranchSiteAnalyzer.Analysis {
        val self = "$pkg/ForgedEchoValue"
        val bytes = valueClass(self, echo, echoSignature) { visitMethod(access, name, descriptor, null, null).body { code(self) } }
        return marksOf(bytes)
    }

    private fun MethodVisitor.echoBody(
        self: String,
        callDescriptor: String = "(Ljava/lang/Object;)Ljava/lang/Object;",
        argumentCasts: List<String> = emptyList(),
        resultCast: String? = string,
        returns: Int = Opcodes.ARETURN,
    ) {
        nullCheck(1)
        visitVarInsn(Opcodes.ILOAD, 0)
        visitMethodInsn(Opcodes.INVOKESTATIC, self, "box-impl", "(I)L$self;", false)
        visitVarInsn(Opcodes.ALOAD, 1)
        argumentCasts.forEach { visitTypeInsn(Opcodes.CHECKCAST, it) }
        visitMethodInsn(Opcodes.INVOKEVIRTUAL, self, "echo", callDescriptor, false)
        resultCast?.let { visitTypeInsn(Opcodes.CHECKCAST, it) }
        visitInsn(returns)
    }

    @Test
    fun `a static that null-checks, boxes, calls the inherited default and casts the result is VALUE_CLASS`() {
        val analysis = echoValue { echoBody(it) }

        assertEquals(GeneratedBy.VALUE_CLASS, analysis.generatedBy("echo-impl", echoDescriptor))
    }

    @Test
    fun `a final static is unmarked`() {
        val analysis = echoValue(access = publicStatic or Opcodes.ACC_FINAL) { echoBody(it) }

        assertEquals(GeneratedBy.NONE, analysis.generatedBy("echo-impl", echoDescriptor))
    }

    @Test
    fun `a static named for another member than the one it calls is unmarked`() {
        val analysis = echoValue(name = "other-impl") { echoBody(it) }

        assertEquals(GeneratedBy.NONE, analysis.generatedBy("other-impl", echoDescriptor))
    }

    @Test
    fun `a static that calls a descriptor no interface method has is unmarked`() {
        val analysis = echoValue { echoBody(it, callDescriptor = "(Ljava/lang/String;)Ljava/lang/Object;") }

        assertEquals(GeneratedBy.NONE, analysis.generatedBy("echo-impl", echoDescriptor))
    }

    @Test
    fun `a static whose result is not the type the interface gives is unmarked`() {
        val uncast = echoValue(descriptor = "(ILjava/lang/String;)Ljava/lang/Object;") { echoBody(it, resultCast = null) }
        val wrongCast =
            echoValue(descriptor = "(ILjava/lang/String;)Ljava/lang/Integer;") { echoBody(it, resultCast = "java/lang/Integer") }

        assertEquals(GeneratedBy.NONE, uncast.generatedBy("echo-impl", "(ILjava/lang/String;)Ljava/lang/Object;"))
        assertEquals(GeneratedBy.NONE, wrongCast.generatedBy("echo-impl", "(ILjava/lang/String;)Ljava/lang/Integer;"))
    }

    @Test
    fun `a static with a cast on an argument the call takes as Object is unmarked`() {
        val analysis = echoValue { echoBody(it, argumentCasts = listOf("java/lang/CharSequence")) }

        assertEquals(GeneratedBy.NONE, analysis.generatedBy("echo-impl", echoDescriptor))
    }

    @Test
    fun `a static with a second conversion at one position is unmarked`() {
        val twice = midValue(listOf("java/lang/CharSequence", "java/lang/CharSequence"))

        assertEquals(GeneratedBy.NONE, twice.generatedBy("f-impl", "(ILjava/lang/String;)I"))
    }

    private val mid = "$pkg/Mid"
    private val midSignature = "Ljava/lang/Object;L$mid<Ljava/lang/String;>;"

    private fun midValue(
        argumentCasts: List<String>,
        descriptor: String = "(ILjava/lang/String;)I",
    ): BranchSiteAnalyzer.Analysis {
        val self = "$pkg/ForgedMidValue"
        val bytes =
            valueClass(self, mid, midSignature) {
                visitMethod(publicStatic, "f-impl", descriptor, null, null).body {
                    nullCheck(1)
                    visitVarInsn(Opcodes.ILOAD, 0)
                    visitMethodInsn(Opcodes.INVOKESTATIC, self, "box-impl", "(I)L$self;", false)
                    visitVarInsn(Opcodes.ALOAD, 1)
                    argumentCasts.forEach { visitTypeInsn(Opcodes.CHECKCAST, it) }
                    visitMethodInsn(Opcodes.INVOKEVIRTUAL, self, "f", "(Ljava/lang/CharSequence;)I", false)
                    visitInsn(Opcodes.IRETURN)
                }
            }
        return marksOf(bytes)
    }

    @Test
    fun `a static that casts its argument to the type the call takes is VALUE_CLASS, to another type it is not`() {
        assertEquals(GeneratedBy.VALUE_CLASS, midValue(listOf("java/lang/CharSequence")).generatedBy("f-impl", "(ILjava/lang/String;)I"))
        assertEquals(GeneratedBy.NONE, midValue(listOf("java/lang/Object")).generatedBy("f-impl", "(ILjava/lang/String;)I"))
        assertEquals(GeneratedBy.NONE, midValue(emptyList()).generatedBy("f-impl", "(ILjava/lang/String;)I"))
    }

    /** A class `Mid<String>` with a stub `f(String)` that casts its argument as [argumentCasts] says. */
    private fun midStub(
        argumentCasts: List<String>,
        receiverCast: Boolean = false,
    ): BranchSiteAnalyzer.Analysis {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER,
            "$pkg/ForgedMidStub",
            midSignature,
            "java/lang/Object",
            arrayOf(mid),
        )
        writer.visitAnnotation("Lkotlin/Metadata;", true).visitEnd()
        writer.visitMethod(Opcodes.ACC_PUBLIC, "f", "(Ljava/lang/String;)I", null, null).body {
            visitVarInsn(Opcodes.ALOAD, 0)
            if (receiverCast) visitTypeInsn(Opcodes.CHECKCAST, mid)
            visitVarInsn(Opcodes.ALOAD, 1)
            argumentCasts.forEach { visitTypeInsn(Opcodes.CHECKCAST, it) }
            visitMethodInsn(Opcodes.INVOKESTATIC, "$mid\$DefaultImpls", "f", "(L$mid;Ljava/lang/CharSequence;)I", false)
            visitInsn(Opcodes.IRETURN)
        }
        writer.visitEnd()
        return marksOf(writer.toByteArray())
    }

    @Test
    fun `a stub that casts its argument to the type the call takes is DEFAULT_IMPLS, to another type or twice it is not`() {
        val descriptor = "(Ljava/lang/String;)I"

        assertEquals(GeneratedBy.DEFAULT_IMPLS, midStub(listOf("java/lang/CharSequence")).generatedBy("f", descriptor))
        assertEquals(GeneratedBy.NONE, midStub(listOf("java/lang/Object")).generatedBy("f", descriptor))
        assertEquals(GeneratedBy.NONE, midStub(listOf("java/lang/Number")).generatedBy("f", descriptor))
        assertEquals(GeneratedBy.NONE, midStub(listOf("java/lang/CharSequence", "java/lang/CharSequence")).generatedBy("f", descriptor))
    }

    @Test
    fun `a stub in a plain class that casts its receiver is unmarked`() {
        assertEquals(
            GeneratedBy.NONE,
            midStub(listOf("java/lang/CharSequence"), receiverCast = true).generatedBy("f", "(Ljava/lang/String;)I"),
        )
    }

    private val holds = "$pkg/Holds"
    private val holdsSignature = "Ljava/lang/Object;L$holds<L$label;>;"
    private val takes = "$pkg/Takes"
    private val takesSignature = "Ljava/lang/Object;L$takes<L$label;>;"

    private fun MethodVisitor.guardedUnbox(
        valueClass: String,
        underlying: String,
        skip: Int = 0,
        branch: Int = Opcodes.IFNULL,
    ) {
        val absent = Label()
        val end = Label()
        visitTypeInsn(Opcodes.CHECKCAST, valueClass)
        visitInsn(Opcodes.DUP)
        visitJumpInsn(branch, absent)
        visitMethodInsn(Opcodes.INVOKEVIRTUAL, valueClass, "unbox-impl", "()$underlying", false)
        visitJumpInsn(Opcodes.GOTO, end)
        visitLabel(absent)
        if (skip != 1) visitInsn(Opcodes.POP)
        if (skip != 2) visitInsn(Opcodes.ACONST_NULL)
        visitLabel(end)
    }

    private fun holdsStub(
        skip: Int = 0,
        branch: Int = Opcodes.IFNULL,
        valueClass: String = label,
    ): BranchSiteAnalyzer.Analysis {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER,
            "$pkg/ForgedHolds",
            holdsSignature,
            "java/lang/Object",
            arrayOf(holds),
        )
        writer.visitAnnotation("Lkotlin/Metadata;", true).visitEnd()
        writer.visitMethod(Opcodes.ACC_PUBLIC, "get-AAAAAAA", "()Ljava/lang/String;", null, null).body {
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESTATIC, "$holds\$DefaultImpls", "get", "(L$holds;)Ljava/lang/Object;", false)
            guardedUnbox(valueClass, "Ljava/lang/String;", skip, branch)
            visitInsn(Opcodes.ARETURN)
        }
        writer.visitEnd()
        return marksOf(writer.toByteArray())
    }

    @Test
    fun `a stub that unboxes null-safely is DEFAULT_IMPLS, and one missing a step of the guard is not`() {
        val descriptor = "()Ljava/lang/String;"

        assertEquals(GeneratedBy.DEFAULT_IMPLS, holdsStub().generatedBy("get-AAAAAAA", descriptor))
        assertEquals(GeneratedBy.NONE, holdsStub(skip = 1).generatedBy("get-AAAAAAA", descriptor))
        assertEquals(GeneratedBy.NONE, holdsStub(skip = 2).generatedBy("get-AAAAAAA", descriptor))
        assertEquals(GeneratedBy.NONE, holdsStub(branch = Opcodes.IFNONNULL).generatedBy("get-AAAAAAA", descriptor))
        assertEquals(GeneratedBy.NONE, holdsStub(valueClass = "$pkg/Holds").generatedBy("get-AAAAAAA", descriptor))
    }

    private fun takesStub(
        skip: Int = 0,
        boxOwner: String = label,
    ): BranchSiteAnalyzer.Analysis {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER,
            "$pkg/ForgedTakes",
            takesSignature,
            "java/lang/Object",
            arrayOf(takes),
        )
        writer.visitAnnotation("Lkotlin/Metadata;", true).visitEnd()
        writer.visitMethod(Opcodes.ACC_PUBLIC, "take-AAAAAAA", "(Ljava/lang/String;)I", null, null).body {
            val absent = Label()
            val end = Label()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitInsn(Opcodes.DUP)
            visitJumpInsn(Opcodes.IFNULL, absent)
            visitMethodInsn(Opcodes.INVOKESTATIC, boxOwner, "box-impl", "(Ljava/lang/String;)L$boxOwner;", false)
            visitJumpInsn(Opcodes.GOTO, end)
            visitLabel(absent)
            if (skip != 1) visitInsn(Opcodes.POP)
            if (skip != 2) visitInsn(Opcodes.ACONST_NULL)
            visitLabel(end)
            visitMethodInsn(Opcodes.INVOKESTATIC, "$takes\$DefaultImpls", "take", "(L$takes;Ljava/lang/Object;)I", false)
            visitInsn(Opcodes.IRETURN)
        }
        writer.visitEnd()
        return marksOf(writer.toByteArray())
    }

    @Test
    fun `a stub that boxes null-safely is DEFAULT_IMPLS, and one missing a step of the guard or boxing a non-value class is not`() {
        val descriptor = "(Ljava/lang/String;)I"

        assertEquals(GeneratedBy.DEFAULT_IMPLS, takesStub().generatedBy("take-AAAAAAA", descriptor))
        assertEquals(GeneratedBy.NONE, takesStub(skip = 1).generatedBy("take-AAAAAAA", descriptor))
        assertEquals(GeneratedBy.NONE, takesStub(skip = 2).generatedBy("take-AAAAAAA", descriptor))
        assertEquals(GeneratedBy.NONE, takesStub(boxOwner = string).generatedBy("take-AAAAAAA", descriptor))
    }

    @Test
    fun `a static that unboxes null-safely over a primitive underlying type is unmarked`() {
        val source = "$pkg/Source"
        val self = "$pkg/ForgedSourceValue"
        val bytes =
            valueClass(self, source, "Ljava/lang/Object;L$source<L$id;>;") {
                visitMethod(publicStatic, "get-AAAAAAA", "(I)I", null, null).body {
                    visitVarInsn(Opcodes.ILOAD, 0)
                    visitMethodInsn(Opcodes.INVOKESTATIC, self, "box-impl", "(I)L$self;", false)
                    visitMethodInsn(Opcodes.INVOKEVIRTUAL, self, "get", "()Ljava/lang/Object;", false)
                    guardedUnbox(id, "I")
                    visitInsn(Opcodes.IRETURN)
                }
                visitMethod(publicStatic, "get-BBBBBBB", "(I)I", null, null).body {
                    visitVarInsn(Opcodes.ILOAD, 0)
                    visitMethodInsn(Opcodes.INVOKESTATIC, self, "box-impl", "(I)L$self;", false)
                    visitMethodInsn(Opcodes.INVOKEVIRTUAL, self, "get", "()Ljava/lang/Object;", false)
                    visitTypeInsn(Opcodes.CHECKCAST, id)
                    visitMethodInsn(Opcodes.INVOKEVIRTUAL, id, "unbox-impl", "()I", false)
                    visitInsn(Opcodes.IRETURN)
                }
            }
        val analysis = marksOf(bytes)

        assertEquals(GeneratedBy.NONE, analysis.generatedBy("get-AAAAAAA", "(I)I"))
        assertEquals(GeneratedBy.VALUE_CLASS, analysis.generatedBy("get-BBBBBBB", "(I)I"))
    }

    @Test
    fun `a static whose cast argument is not the type the interface gives is unmarked`() {
        val analysis = midValue(listOf("java/lang/CharSequence"), descriptor = "(ILjava/lang/Integer;)I")

        assertEquals(GeneratedBy.NONE, analysis.generatedBy("f-impl", "(ILjava/lang/Integer;)I"))
    }

    private val store = "$pkg/ValueStore"

    /** A class `ValueStore<String>` with a stub `put(String)` that casts its argument to [castTo], or not at all. */
    private fun storeStub(castTo: String?): BranchSiteAnalyzer.Analysis {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        val signature = "Ljava/lang/Object;L$store<Ljava/lang/String;>;"
        writer.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER,
            "$pkg/ForgedStoreStub",
            signature,
            "java/lang/Object",
            arrayOf(store),
        )
        writer.visitAnnotation("Lkotlin/Metadata;", true).visitEnd()
        writer.visitMethod(Opcodes.ACC_PUBLIC, "put", "(Ljava/lang/String;)Ljava/lang/String;", null, null).body {
            visitVarInsn(Opcodes.ALOAD, 0)
            visitVarInsn(Opcodes.ALOAD, 1)
            castTo?.let { visitTypeInsn(Opcodes.CHECKCAST, it) }
            visitMethodInsn(Opcodes.INVOKESTATIC, "$store\$DefaultImpls", "put", "(L$store;Ljava/lang/Object;)Ljava/lang/Object;", false)
            visitTypeInsn(Opcodes.CHECKCAST, string)
            visitInsn(Opcodes.ARETURN)
        }
        writer.visitEnd()
        return marksOf(writer.toByteArray())
    }

    @Test
    fun `a stub that casts its argument to Object is unmarked`() {
        val descriptor = "(Ljava/lang/String;)Ljava/lang/String;"

        assertEquals(GeneratedBy.DEFAULT_IMPLS, storeStub(null).generatedBy("put", descriptor))
        assertEquals(GeneratedBy.NONE, storeStub("java/lang/Object").generatedBy("put", descriptor))
    }

    /** A stub `Mid<CharSequence>.f(CharSequence)` that casts its argument to the type it already has. */
    @Test
    fun `a stub that casts an argument to its own type is unmarked`() {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        val signature = "Ljava/lang/Object;L$mid<Ljava/lang/CharSequence;>;"
        writer.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER,
            "$pkg/ForgedMidWide",
            signature,
            "java/lang/Object",
            arrayOf(mid),
        )
        writer.visitAnnotation("Lkotlin/Metadata;", true).visitEnd()
        writer.visitMethod(Opcodes.ACC_PUBLIC, "f", "(Ljava/lang/CharSequence;)I", null, null).body {
            visitVarInsn(Opcodes.ALOAD, 0)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitTypeInsn(Opcodes.CHECKCAST, "java/lang/CharSequence")
            visitMethodInsn(Opcodes.INVOKESTATIC, "$mid\$DefaultImpls", "f", "(L$mid;Ljava/lang/CharSequence;)I", false)
            visitInsn(Opcodes.IRETURN)
        }
        writer.visitEnd()

        assertEquals(GeneratedBy.NONE, marksOf(writer.toByteArray()).generatedBy("f", "(Ljava/lang/CharSequence;)I"))
    }

    /** A `DefaultImpls` method with no generic signature, so no type variable, and a stub whose own signature erases to the parameter. */
    @Test
    fun `a stub that casts an argument where the called method has no type variable is unmarked`() {
        val iface = "$pkg/ForgedPlain"
        val impls = "$iface\$DefaultImpls"
        val implsWriter = ClassWriter(ClassWriter.COMPUTE_MAXS)
        implsWriter.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SUPER, impls, null, "java/lang/Object", null)
        implsWriter.visitAnnotation("Lkotlin/Metadata;", true).visitEnd()
        implsWriter.visitMethod(publicStatic, "g", "(L$iface;Ljava/lang/CharSequence;)I", null, null).body {
            visitInsn(Opcodes.ICONST_0)
            visitInsn(Opcodes.IRETURN)
        }
        implsWriter.visitEnd()
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER,
            "$pkg/ForgedPlainImpl",
            null,
            "java/lang/Object",
            arrayOf(iface),
        )
        writer.visitAnnotation("Lkotlin/Metadata;", true).visitEnd()
        writer.visitMethod(Opcodes.ACC_PUBLIC, "g", "(Ljava/lang/String;)I", "(Ljava/lang/CharSequence;)I", null).body {
            visitVarInsn(Opcodes.ALOAD, 0)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitTypeInsn(Opcodes.CHECKCAST, "java/lang/CharSequence")
            visitMethodInsn(Opcodes.INVOKESTATIC, impls, "g", "(L$iface;Ljava/lang/CharSequence;)I", false)
            visitInsn(Opcodes.IRETURN)
        }
        writer.visitEnd()
        val analysis =
            BranchSiteAnalyzer.analyze(
                writer.toByteArray(),
                { name -> if (name == impls) implsWriter.toByteArray() else build.lookup(name) },
                includePackages = listOf("com.example.target"),
            ) { _, _ -> true }

        assertEquals(GeneratedBy.NONE, analysis.generatedBy("g", "(Ljava/lang/String;)I"))
    }
}
