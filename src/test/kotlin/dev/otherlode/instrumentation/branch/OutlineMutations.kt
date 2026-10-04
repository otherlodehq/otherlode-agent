package dev.otherlode.instrumentation.branch

import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.ClassWriter
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes

/**
 * Edits of real compiler output that change one instruction of a shape the agent reads, so that
 * the shape no longer reads while the operand still comes from where the compiler put it. Each
 * edit applies to one method and leaves every tracked jump and switch where it was.
 */
internal object OutlineMutations {
    private const val SUSPENDED_MARKER = "getCOROUTINE_SUSPENDED"

    /** [bytes] with [edit] applied to the body of method [methodName]. */
    fun rewrite(
        bytes: ByteArray,
        methodName: String,
        edit: (MethodVisitor) -> MethodVisitor,
    ): ByteArray {
        val reader = ClassReader(bytes)
        val writer = ClassWriter(0)
        reader.accept(
            object : ClassVisitor(Opcodes.ASM9, writer) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor? {
                    val visitor = super.visitMethod(access, name, descriptor, signature, exceptions)
                    return if (name == methodName && visitor != null) edit(visitor) else visitor
                }
            },
            0,
        )
        return writer.toByteArray()
    }

    /** A `nop` before every `tableswitch` or `lookupswitch`, which separates a switch from the `getfield label` before it. */
    fun nopBeforeSwitch(next: MethodVisitor): MethodVisitor =
        object : MethodVisitor(Opcodes.ASM9, next) {
            override fun visitTableSwitchInsn(
                min: Int,
                max: Int,
                dflt: net.bytebuddy.jar.asm.Label,
                vararg labels: net.bytebuddy.jar.asm.Label,
            ) {
                super.visitInsn(Opcodes.NOP)
                super.visitTableSwitchInsn(min, max, dflt, *labels)
            }
        }

    /** A `nop` between `getfield label` and the `ldc Int.MIN_VALUE` of the re-entry test. */
    fun nopInReentryTest(next: MethodVisitor): MethodVisitor =
        object : MethodVisitor(Opcodes.ASM9, next) {
            private var afterLabelRead = false

            override fun visitFieldInsn(
                opcode: Int,
                owner: String,
                name: String,
                descriptor: String,
            ) {
                afterLabelRead = opcode == Opcodes.GETFIELD && name == "label"
                super.visitFieldInsn(opcode, owner, name, descriptor)
            }

            override fun visitLdcInsn(value: Any?) {
                if (afterLabelRead && value == Int.MIN_VALUE) super.visitInsn(Opcodes.NOP)
                afterLabelRead = false
                super.visitLdcInsn(value)
            }

            override fun visitInsn(opcode: Int) {
                afterLabelRead = false
                super.visitInsn(opcode)
            }

            override fun visitVarInsn(
                opcode: Int,
                varIndex: Int,
            ) {
                afterLabelRead = false
                super.visitVarInsn(opcode, varIndex)
            }
        }

    /** A `nop` between the `dup` and the `getCOROUTINE_SUSPENDED` call of the stack-form compare. */
    fun nopBetweenDupAndMarker(next: MethodVisitor): MethodVisitor =
        object : MethodVisitor(Opcodes.ASM9, next) {
            private var afterDup = false

            override fun visitInsn(opcode: Int) {
                afterDup = opcode == Opcodes.DUP
                super.visitInsn(opcode)
            }

            override fun visitMethodInsn(
                opcode: Int,
                owner: String,
                name: String,
                descriptor: String,
                isInterface: Boolean,
            ) {
                if (afterDup && name == SUSPENDED_MARKER) super.visitInsn(Opcodes.NOP)
                afterDup = false
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
            }

            override fun visitVarInsn(
                opcode: Int,
                varIndex: Int,
            ) {
                afterDup = false
                super.visitVarInsn(opcode, varIndex)
            }
        }

    /** Two `nop`s before every reference compare, which push the load of the marker's local out of the two instructions the shape reads. */
    fun nopsBeforeReferenceCompare(next: MethodVisitor): MethodVisitor =
        object : MethodVisitor(Opcodes.ASM9, next) {
            override fun visitJumpInsn(
                opcode: Int,
                label: net.bytebuddy.jar.asm.Label,
            ) {
                if (opcode == Opcodes.IF_ACMPEQ || opcode == Opcodes.IF_ACMPNE) {
                    super.visitInsn(Opcodes.NOP)
                    super.visitInsn(Opcodes.NOP)
                }
                super.visitJumpInsn(opcode, label)
            }
        }

    /** The continuation `instanceof` of the head check replaced by an `instanceof Object`. */
    fun instanceOfObject(next: MethodVisitor): MethodVisitor =
        object : MethodVisitor(Opcodes.ASM9, next) {
            override fun visitTypeInsn(
                opcode: Int,
                type: String,
            ) {
                super.visitTypeInsn(opcode, if (opcode == Opcodes.INSTANCEOF) "java/lang/Object" else type)
            }
        }

    /** A `nop` right after the first `astore`, which breaks the prologue of a string switch lowering. */
    fun nopAfterFirstStore(next: MethodVisitor): MethodVisitor =
        object : MethodVisitor(Opcodes.ASM9, next) {
            private var done = false

            override fun visitVarInsn(
                opcode: Int,
                varIndex: Int,
            ) {
                super.visitVarInsn(opcode, varIndex)
                if (!done && opcode == Opcodes.ASTORE) {
                    done = true
                    super.visitInsn(Opcodes.NOP)
                }
            }
        }
}
