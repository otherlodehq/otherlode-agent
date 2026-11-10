package dev.otherlode.instrumentation.branch

import dev.otherlode.export.GeneratedBy
import net.bytebuddy.jar.asm.ClassWriter
import net.bytebuddy.jar.asm.Label
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Shapes kotlinc does not write for a `data object`, built with ASM so each condition of the rule
 * is pinned alone: one instruction off in a body, a member that is not public, carries a line
 * number or is missing, and a class without the object's own `INSTANCE` or without Kotlin metadata.
 */
class DataObjectForgedShapesTest {
    private val name = "com/example/target/ForgedDataObject"

    private val toStringKey = "toString" to "()Ljava/lang/String;"
    private val hashCodeKey = "hashCode" to "()I"
    private val equalsKey = "equals" to "(Ljava/lang/Object;)Z"

    private fun marks(shape: Shape = Shape()): Map<Pair<String, String>, GeneratedBy> {
        val analysis = BranchSiteAnalyzer.analyze(build(shape)) { _, _ -> true }
        return listOf(toStringKey, hashCodeKey, equalsKey).associateWith { analysis.generatedBy(it.first, it.second) }
    }

    private fun allMarked(shape: Shape) = marks(shape).values.all { it == GeneratedBy.DATA_CLASS }

    private fun noneMarked(shape: Shape) = marks(shape).values.all { it == GeneratedBy.NONE }

    private class Shape(
        val metadata: Boolean = true,
        val instanceType: String? = "self",
        val instanceStatic: Boolean = true,
        val toStringBody: (MethodVisitor) -> Unit = { it.ldcReturn("ForgedDataObject") },
        val hashCodeBody: (MethodVisitor) -> Unit = { it.hashReturn(123456789) },
        val equalsBody: (MethodVisitor, String) -> Unit = { visitor, own -> visitor.identityThenInstanceOf(own) },
        val toStringAccess: Int = Opcodes.ACC_PUBLIC,
        val hashCodeAccess: Int = Opcodes.ACC_PUBLIC,
        val equalsAccess: Int = Opcodes.ACC_PUBLIC,
        val omit: String? = null,
        val lineNumberIn: String? = null,
    )

    private fun build(shape: Shape): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V1_6, Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SUPER, name, null, "java/lang/Object", null)
        if (shape.metadata) writer.visitAnnotation("Lkotlin/Metadata;", true).visitEnd()
        shape.instanceType?.let { type ->
            val descriptor = if (type == "self") "L$name;" else type
            val access = Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or if (shape.instanceStatic) Opcodes.ACC_STATIC else 0
            writer.visitField(access, "INSTANCE", descriptor, null, null).visitEnd()
        }
        writer.method(shape, "toString", "()Ljava/lang/String;", shape.toStringAccess) { shape.toStringBody(it) }
        writer.method(shape, "hashCode", "()I", shape.hashCodeAccess) { shape.hashCodeBody(it) }
        writer.method(shape, "equals", "(Ljava/lang/Object;)Z", shape.equalsAccess) { shape.equalsBody(it, name) }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun ClassWriter.method(
        shape: Shape,
        methodName: String,
        descriptor: String,
        access: Int,
        body: (MethodVisitor) -> Unit,
    ) {
        if (shape.omit == methodName) return
        visitMethod(access, methodName, descriptor, null, null).apply {
            visitCode()
            if (shape.lineNumberIn == methodName) {
                val start = Label()
                visitLabel(start)
                visitLineNumber(3, start)
            }
            body(this)
            visitMaxs(0, 0)
            visitEnd()
        }
    }

    @Test
    fun `the shape kotlinc writes is marked DATA_CLASS, so the forged shapes below differ from it by one condition`() {
        assertEquals(true, allMarked(Shape()))
    }

    @Test
    fun `a hashCode with one instruction more is not marked, and neither are the other two`() {
        val shape =
            Shape(
                hashCodeBody = {
                    it.visitLdcInsn(123456789)
                    it.visitInsn(Opcodes.ICONST_1)
                    it.visitInsn(Opcodes.IADD)
                    it.visitInsn(Opcodes.IRETURN)
                },
            )

        assertEquals(true, noneMarked(shape))
    }

    @Test
    fun `an equals that does not cast and pop is not marked`() {
        val shape =
            Shape(
                equalsBody = { visitor, own ->
                    val notSame = Label()
                    val sameType = Label()
                    visitor.visitVarInsn(Opcodes.ALOAD, 0)
                    visitor.visitVarInsn(Opcodes.ALOAD, 1)
                    visitor.visitJumpInsn(Opcodes.IF_ACMPNE, notSame)
                    visitor.visitInsn(Opcodes.ICONST_1)
                    visitor.visitInsn(Opcodes.IRETURN)
                    visitor.visitLabel(notSame)
                    visitor.visitVarInsn(Opcodes.ALOAD, 1)
                    visitor.visitTypeInsn(Opcodes.INSTANCEOF, own)
                    visitor.visitJumpInsn(Opcodes.IFNE, sameType)
                    visitor.visitInsn(Opcodes.ICONST_0)
                    visitor.visitInsn(Opcodes.IRETURN)
                    visitor.visitLabel(sameType)
                    visitor.visitInsn(Opcodes.ICONST_1)
                    visitor.visitInsn(Opcodes.IRETURN)
                },
            )

        assertEquals(true, noneMarked(shape))
    }

    @Test
    fun `an equals that tests another class is not marked`() {
        val shape = Shape(equalsBody = { visitor, _ -> visitor.identityThenInstanceOf("java/lang/String") })

        assertEquals(true, noneMarked(shape))
    }

    @Test
    fun `a toString that returns a name other than the class's simple name is not marked`() {
        assertEquals(true, noneMarked(Shape(toStringBody = { it.ldcReturn("Other") })))
    }

    @Test
    fun `a hashCode that returns something other than a constant is not marked`() {
        val shape =
            Shape(
                hashCodeBody = {
                    it.visitVarInsn(Opcodes.ALOAD, 0)
                    it.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "identityHashCode", "(Ljava/lang/Object;)I", false)
                    it.visitInsn(Opcodes.IRETURN)
                },
            )

        assertEquals(true, noneMarked(shape))
    }

    @Test
    fun `a member that is not public is not marked`() {
        assertEquals(true, noneMarked(Shape(toStringAccess = 0)))
        assertEquals(true, noneMarked(Shape(hashCodeAccess = Opcodes.ACC_PROTECTED)))
        assertEquals(true, noneMarked(Shape(equalsAccess = Opcodes.ACC_PUBLIC or Opcodes.ACC_SYNTHETIC)))
    }

    @Test
    fun `a member with a line number is the adopter's, and the other two stay unmarked with it`() {
        listOf("toString", "hashCode", "equals").forEach { member ->
            assertEquals(true, noneMarked(Shape(lineNumberIn = member)), member)
        }
    }

    @Test
    fun `a class missing one of the three members is not marked`() {
        listOf("toString", "hashCode", "equals").forEach { member ->
            assertEquals(true, noneMarked(Shape(omit = member)), member)
        }
    }

    @Test
    fun `a class without a static INSTANCE of its own type is not marked`() {
        assertEquals(true, noneMarked(Shape(instanceType = null)))
        assertEquals(true, noneMarked(Shape(instanceType = "Ljava/lang/Object;")))
        assertEquals(true, noneMarked(Shape(instanceStatic = false)))
    }

    @Test
    fun `a class without Kotlin metadata is not marked`() {
        assertEquals(true, noneMarked(Shape(metadata = false)))
    }
}

private fun MethodVisitor.ldcReturn(text: String) {
    visitLdcInsn(text)
    visitInsn(Opcodes.ARETURN)
}

private fun MethodVisitor.hashReturn(value: Int) {
    visitLdcInsn(value)
    visitInsn(Opcodes.IRETURN)
}

private fun MethodVisitor.identityThenInstanceOf(type: String) {
    val notSame = Label()
    val sameType = Label()
    visitVarInsn(Opcodes.ALOAD, 0)
    visitVarInsn(Opcodes.ALOAD, 1)
    visitJumpInsn(Opcodes.IF_ACMPNE, notSame)
    visitInsn(Opcodes.ICONST_1)
    visitInsn(Opcodes.IRETURN)
    visitLabel(notSame)
    visitVarInsn(Opcodes.ALOAD, 1)
    visitTypeInsn(Opcodes.INSTANCEOF, type)
    visitJumpInsn(Opcodes.IFNE, sameType)
    visitInsn(Opcodes.ICONST_0)
    visitInsn(Opcodes.IRETURN)
    visitLabel(sameType)
    visitVarInsn(Opcodes.ALOAD, 1)
    visitTypeInsn(Opcodes.CHECKCAST, type)
    visitInsn(Opcodes.POP)
    visitInsn(Opcodes.ICONST_1)
    visitInsn(Opcodes.IRETURN)
}
