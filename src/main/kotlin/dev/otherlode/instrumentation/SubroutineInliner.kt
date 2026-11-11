package dev.otherlode.instrumentation

import net.bytebuddy.asm.AsmVisitorWrapper
import net.bytebuddy.description.field.FieldDescription
import net.bytebuddy.description.field.FieldList
import net.bytebuddy.description.method.MethodList
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.implementation.Implementation
import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.ClassWriter
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import net.bytebuddy.jar.asm.commons.JSRInlinerAdapter
import net.bytebuddy.pool.TypePool

internal object SubroutineInliner {
    const val FIRST_SUBROUTINE_FREE_VERSION = 51

    fun mayHaveSubroutines(majorVersion: Int): Boolean = majorVersion in 1 until FIRST_SUBROUTINE_FREE_VERSION

    fun inline(classFile: ByteArray): ByteArray {
        if (classFile.size < HEADER_BYTES) return classFile
        val version = ((classFile[6].toInt() and 0xff) shl 8) or (classFile[7].toInt() and 0xff)
        if (!mayHaveSubroutines(version) || !hasSubroutines(classFile)) return classFile
        val writer = ClassWriter(0)
        ClassReader(classFile).accept(
            object : ClassVisitor(Opcodes.ASM9, writer) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor =
                    JSRInlinerAdapter(
                        super.visitMethod(access, name, descriptor, signature, exceptions),
                        access,
                        name,
                        descriptor,
                        signature,
                        exceptions,
                    )
            },
            0,
        )
        return writer.toByteArray()
    }

    private fun hasSubroutines(classFile: ByteArray): Boolean {
        var found = false
        ClassReader(classFile).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor =
                    object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitJumpInsn(
                            opcode: Int,
                            label: net.bytebuddy.jar.asm.Label,
                        ) {
                            if (opcode == Opcodes.JSR) found = true
                        }

                        override fun visitVarInsn(
                            opcode: Int,
                            varIndex: Int,
                        ) {
                            if (opcode == Opcodes.RET) found = true
                        }
                    }
            },
            ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
        )
        return found
    }

    // Add this wrapper after every probe wrapper. The last one added is the first to see a method,
    // and no probe wrapper may see a `jsr`.
    fun wrapper(): AsmVisitorWrapper =
        object : AsmVisitorWrapper.AbstractBase() {
            override fun wrap(
                instrumentedType: TypeDescription,
                classVisitor: ClassVisitor,
                implementationContext: Implementation.Context,
                typePool: TypePool,
                fields: FieldList<FieldDescription.InDefinedShape>,
                methods: MethodList<*>,
                writerFlags: Int,
                readerFlags: Int,
            ): ClassVisitor =
                object : ClassVisitor(Opcodes.ASM9, classVisitor) {
                    private var inlining = false

                    override fun visit(
                        version: Int,
                        access: Int,
                        name: String?,
                        signature: String?,
                        superName: String?,
                        interfaces: Array<out String>?,
                    ) {
                        inlining = mayHaveSubroutines(version and 0xffff)
                        super.visit(version, access, name, signature, superName, interfaces)
                    }

                    override fun visitMethod(
                        access: Int,
                        name: String,
                        descriptor: String,
                        signature: String?,
                        exceptions: Array<out String>?,
                    ): MethodVisitor {
                        val delegate = super.visitMethod(access, name, descriptor, signature, exceptions)
                        return if (inlining && delegate != null) {
                            JSRInlinerAdapter(delegate, access, name, descriptor, signature, exceptions)
                        } else {
                            delegate
                        }
                    }
                }
        }

    private const val HEADER_BYTES = 8
}
