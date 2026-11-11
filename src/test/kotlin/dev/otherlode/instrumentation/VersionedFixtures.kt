package dev.otherlode.instrumentation

import net.bytebuddy.jar.asm.ClassWriter
import net.bytebuddy.jar.asm.Label
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes

internal object VersionedFixtures {
    const val PACKAGE = "com.example.matrix"

    val VERSIONS = listOf(45, 46, 47, 48, 49, 50, 51, 52, 55, 61, 65, 69)

    class Fixture(
        val internalName: String,
        val bytes: ByteArray,
    ) {
        val name: String get() = internalName.replace('/', '.')
    }

    fun internalName(
        version: Int,
        simpleName: String,
    ): String = "${PACKAGE.replace('.', '/')}/v$version/$simpleName"

    private fun asmVersion(major: Int): Int = if (major == 45) Opcodes.V1_1 else major

    private fun writer(version: Int): ClassWriter = ClassWriter(if (version >= 50) ClassWriter.COMPUTE_FRAMES else ClassWriter.COMPUTE_MAXS)

    private fun MethodVisitor.line(
        number: Int,
        label: Label,
    ) {
        visitLabel(label)
        visitLineNumber(number, label)
    }

    private fun ClassWriter.constructor(superName: String = "java/lang/Object") {
        val mv = visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
        mv.visitCode()
        mv.line(3, Label())
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false)
        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    private fun ClassWriter.pick() {
        val mv = visitMethod(Opcodes.ACC_PUBLIC, "pick", "(I)I", null, null)
        mv.visitCode()
        val negative = Label()
        mv.line(5, Label())
        mv.visitVarInsn(Opcodes.ILOAD, 1)
        mv.visitJumpInsn(Opcodes.IFLE, negative)
        mv.visitInsn(Opcodes.ICONST_1)
        mv.visitInsn(Opcodes.IRETURN)
        mv.line(6, negative)
        mv.visitInsn(Opcodes.ICONST_M1)
        mv.visitInsn(Opcodes.IRETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    private fun ClassWriter.initializer(
        owner: String,
        field: String,
        value: Int,
    ) {
        val mv = visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null)
        mv.visitCode()
        mv.line(2, Label())
        mv.visitIntInsn(Opcodes.BIPUSH, value)
        mv.visitFieldInsn(Opcodes.PUTSTATIC, owner, field, "I")
        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    fun plain(version: Int): Fixture {
        val name = internalName(version, "Plain")
        val cw = writer(version)
        cw.visit(asmVersion(version), Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER, name, null, "java/lang/Object", null)
        cw.visitSource("Plain.java", null)
        cw.visitField(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "counter", "I", null, null).visitEnd()
        cw.initializer(name, "counter", 7)
        cw.constructor()
        cw.pick()
        cw.visitEnd()
        return Fixture(name, cw.toByteArray())
    }

    fun constants(version: Int): Fixture {
        val name = internalName(version, "Constants")
        val cw = writer(version)
        cw.visit(
            asmVersion(version),
            Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT,
            name,
            null,
            "java/lang/Object",
            null,
        )
        cw.visitSource("Constants.java", null)
        cw.visitField(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL, "LIMIT", "I", null, null).visitEnd()
        cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, "value", "()I", null, null).visitEnd()
        cw.initializer(name, "LIMIT", 9)
        cw.visitEnd()
        return Fixture(name, cw.toByteArray())
    }

    fun defaults(version: Int): List<Fixture> {
        val name = internalName(version, "Defaults")
        val cw = writer(version)
        cw.visit(
            asmVersion(version),
            Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT,
            name,
            null,
            "java/lang/Object",
            null,
        )
        cw.visitSource("Defaults.java", null)
        val twice = cw.visitMethod(Opcodes.ACC_PUBLIC, "twice", "(I)I", null, null)
        twice.visitCode()
        val negative = Label()
        twice.line(5, Label())
        twice.visitVarInsn(Opcodes.ILOAD, 1)
        twice.visitJumpInsn(Opcodes.IFLT, negative)
        twice.visitVarInsn(Opcodes.ILOAD, 1)
        twice.visitInsn(Opcodes.ICONST_2)
        twice.visitInsn(Opcodes.IMUL)
        twice.visitInsn(Opcodes.IRETURN)
        twice.line(6, negative)
        twice.visitInsn(Opcodes.ICONST_0)
        twice.visitInsn(Opcodes.IRETURN)
        twice.visitMaxs(0, 0)
        twice.visitEnd()
        val zero = cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "zero", "()I", null, null)
        zero.visitCode()
        zero.line(8, Label())
        zero.visitInsn(Opcodes.ICONST_0)
        zero.visitInsn(Opcodes.IRETURN)
        zero.visitMaxs(0, 0)
        zero.visitEnd()
        cw.visitEnd()

        val implName = internalName(version, "DefaultsImpl")
        val impl = writer(version)
        impl.visit(asmVersion(version), Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER, implName, null, "java/lang/Object", arrayOf(name))
        impl.visitSource("DefaultsImpl.java", null)
        impl.constructor()
        impl.visitEnd()
        return listOf(Fixture(name, cw.toByteArray()), Fixture(implName, impl.toByteArray()))
    }

    fun annotationType(version: Int): Fixture {
        val name = internalName(version, "Marker")
        val cw = writer(version)
        cw.visit(
            asmVersion(version),
            Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT or Opcodes.ACC_ANNOTATION,
            name,
            null,
            "java/lang/Object",
            arrayOf("java/lang/annotation/Annotation"),
        )
        cw.visitSource("Marker.java", null)
        cw.visitAnnotation("Ljava/lang/annotation/Retention;", true).apply {
            visitEnum("value", "Ljava/lang/annotation/RetentionPolicy;", "RUNTIME")
            visitEnd()
        }
        cw.visitField(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL, "LEVEL", "I", null, null).visitEnd()
        cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, "level", "()I", null, null).visitEnd()
        cw.initializer(name, "LEVEL", 3)
        cw.visitEnd()
        return Fixture(name, cw.toByteArray())
    }

    fun annotated(
        version: Int,
        marker: Fixture,
    ): Fixture {
        val name = internalName(version, "Carrier")
        val cw = writer(version)
        cw.visit(asmVersion(version), Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER, name, null, "java/lang/Object", null)
        cw.visitSource("Carrier.java", null)
        cw.visitAnnotation("L${marker.internalName};", true).apply {
            visit("level", 5)
            visitEnd()
        }
        cw.constructor()
        cw.visitEnd()
        return Fixture(name, cw.toByteArray())
    }

    fun straight(version: Int): Fixture {
        val name = internalName(version, "Straight")
        val cw = writer(version)
        cw.visit(asmVersion(version), Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER, name, null, "java/lang/Object", null)
        cw.visitSource("Straight.java", null)
        cw.constructor()
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "seven", "()I", null, null)
        mv.visitCode()
        val start = Label()
        mv.line(1, start)
        mv.visitIntInsn(Opcodes.BIPUSH, 7)
        mv.visitInsn(Opcodes.IRETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
        cw.visitEnd()
        return Fixture(name, cw.toByteArray())
    }

    fun subroutine(version: Int): Fixture {
        val name = internalName(version, "Finally")
        // ASM cannot compute frames over jsr; a version 50 class without a StackMapTable is verified by type inference.
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
        cw.visit(asmVersion(version), Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER, name, null, "java/lang/Object", null)
        cw.visitSource("Finally.java", null)
        cw.visitField(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "hits", "I", null, null).visitEnd()
        cw.constructor()
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "f", "(I)I", null, null)
        mv.visitCode()
        val negative = Label()
        val subroutine = Label()
        val skip = Label()
        mv.line(10, Label())
        mv.visitVarInsn(Opcodes.ILOAD, 0)
        mv.visitJumpInsn(Opcodes.IFLE, negative)
        mv.visitJumpInsn(Opcodes.JSR, subroutine)
        mv.visitInsn(Opcodes.ICONST_1)
        mv.visitInsn(Opcodes.IRETURN)
        mv.line(12, negative)
        mv.visitJumpInsn(Opcodes.JSR, subroutine)
        mv.visitInsn(Opcodes.ICONST_M1)
        mv.visitInsn(Opcodes.IRETURN)
        mv.line(14, subroutine)
        mv.visitVarInsn(Opcodes.ASTORE, 1)
        mv.visitVarInsn(Opcodes.ILOAD, 0)
        mv.visitJumpInsn(Opcodes.IFNE, skip)
        mv.visitFieldInsn(Opcodes.GETSTATIC, name, "hits", "I")
        mv.visitInsn(Opcodes.ICONST_1)
        mv.visitInsn(Opcodes.IADD)
        mv.visitFieldInsn(Opcodes.PUTSTATIC, name, "hits", "I")
        mv.line(16, skip)
        mv.visitVarInsn(Opcodes.RET, 1)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
        cw.visitEnd()
        return Fixture(name, cw.toByteArray())
    }

    fun annotated(version: Int): Fixture {
        val name = internalName(version, "Annotated")
        val cw = writer(version)
        cw.visit(asmVersion(version), Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER, name, null, "java/lang/Object", null)
        cw.visitSource("Annotated.java", null)
        cw.visitAnnotation("Ljava/lang/Deprecated;", true).visitEnd()
        cw.constructor()
        cw.pick()
        cw.visitEnd()
        return Fixture(name, cw.toByteArray())
    }
}
