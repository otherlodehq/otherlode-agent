package dev.otherlode.instrumentation

import net.bytebuddy.asm.AsmVisitorWrapper
import net.bytebuddy.description.field.FieldDescription
import net.bytebuddy.description.field.FieldList
import net.bytebuddy.description.method.MethodList
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.implementation.Implementation
import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.Handle
import net.bytebuddy.jar.asm.Label
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import net.bytebuddy.pool.TypePool

/**
 * The returned and threw slots of a `<clinit>` (ADR 0071), written as ASM events.
 *
 * The returned slot is incremented before each `return` of the original body. The threw slot is
 * incremented in one catch-all handler that wraps the original body, after the prelude and the
 * entry increment, and rethrows the caught throwable. The handler is the last entry of the
 * exception table, so it sees only what leaves the body, and the frames the class arrived with are
 * kept.
 */
internal object InitializerProbes {
    private const val TYPE_INITIALIZER = "<clinit>"
    private const val VOID_DESCRIPTOR = "()V"
    private const val THROWABLE = "java/lang/Throwable"
    private const val MAJOR_VERSION_MASK = 0xffff

    /** The handler holds the caught throwable under a probe increment. */
    private const val HANDLER_STACK = 1 + MethodProbes.PROBE_STACK

    /**
     * The wrapper that writes both slots into the `<clinit>` of a class woven in [form].
     * [classHasFrames] says whether a version 50 class carries stack map frames.
     */
    fun wrapper(
        form: ProbeArrayForm,
        returnedSlot: Int,
        threwSlot: Int,
        classHasFrames: Boolean,
    ): AsmVisitorWrapper =
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
            ): ClassVisitor {
                val expanded = readerFlags and ClassReader.EXPAND_FRAMES != 0
                return object : ClassVisitor(Opcodes.ASM9, classVisitor) {
                    private var owner = instrumentedType.internalName
                    private var frame = FrameKind.NONE

                    override fun visit(
                        version: Int,
                        access: Int,
                        name: String,
                        signature: String?,
                        superName: String?,
                        interfaces: Array<out String>?,
                    ) {
                        owner = name
                        val major = version and MAJOR_VERSION_MASK
                        val withFrames = major >= Opcodes.V1_7 || (major == Opcodes.V1_6 && classHasFrames)
                        frame =
                            when {
                                !withFrames -> FrameKind.NONE
                                expanded -> FrameKind.EXPANDED
                                else -> FrameKind.COMPRESSED
                            }
                        super.visit(version, access, name, signature, superName, interfaces)
                    }

                    override fun visitMethod(
                        access: Int,
                        name: String,
                        descriptor: String,
                        signature: String?,
                        exceptions: Array<out String>?,
                    ): MethodVisitor? {
                        val visitor = super.visitMethod(access, name, descriptor, signature, exceptions)
                        if (visitor == null || name != TYPE_INITIALIZER || descriptor != VOID_DESCRIPTOR) return visitor
                        return Outcomes(visitor, form, owner, returnedSlot, threwSlot, frame)
                    }
                }
            }
        }

    private enum class FrameKind { NONE, EXPANDED, COMPRESSED }

    private class Outcomes(
        delegate: MethodVisitor,
        private val form: ProbeArrayForm,
        private val owner: String,
        private val returnedSlot: Int,
        private val threwSlot: Int,
        private val frame: FrameKind,
    ) : MethodVisitor(Opcodes.ASM9, delegate) {
        private val start = Label()
        private val end = Label()
        private val handler = Label()
        private var started = false

        override fun visitInsn(opcode: Int) {
            begin()
            if (opcode == Opcodes.RETURN) increment(returnedSlot)
            super.visitInsn(opcode)
        }

        override fun visitLabel(label: Label) {
            begin()
            super.visitLabel(label)
        }

        override fun visitFrame(
            type: Int,
            numLocal: Int,
            local: Array<out Any>?,
            numStack: Int,
            stack: Array<out Any>?,
        ) {
            begin()
            super.visitFrame(type, numLocal, local, numStack, stack)
        }

        override fun visitIntInsn(
            opcode: Int,
            operand: Int,
        ) {
            begin()
            super.visitIntInsn(opcode, operand)
        }

        override fun visitVarInsn(
            opcode: Int,
            varIndex: Int,
        ) {
            begin()
            super.visitVarInsn(opcode, varIndex)
        }

        override fun visitTypeInsn(
            opcode: Int,
            type: String,
        ) {
            begin()
            super.visitTypeInsn(opcode, type)
        }

        override fun visitFieldInsn(
            opcode: Int,
            owner: String,
            name: String,
            descriptor: String,
        ) {
            begin()
            super.visitFieldInsn(opcode, owner, name, descriptor)
        }

        override fun visitMethodInsn(
            opcode: Int,
            owner: String,
            name: String,
            descriptor: String,
            isInterface: Boolean,
        ) {
            begin()
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
        }

        override fun visitInvokeDynamicInsn(
            name: String,
            descriptor: String,
            bootstrapMethodHandle: Handle,
            vararg bootstrapMethodArguments: Any,
        ) {
            begin()
            super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, *bootstrapMethodArguments)
        }

        override fun visitJumpInsn(
            opcode: Int,
            label: Label,
        ) {
            begin()
            super.visitJumpInsn(opcode, label)
        }

        override fun visitLdcInsn(value: Any) {
            begin()
            super.visitLdcInsn(value)
        }

        override fun visitIincInsn(
            varIndex: Int,
            increment: Int,
        ) {
            begin()
            super.visitIincInsn(varIndex, increment)
        }

        override fun visitTableSwitchInsn(
            min: Int,
            max: Int,
            dflt: Label,
            vararg labels: Label,
        ) {
            begin()
            super.visitTableSwitchInsn(min, max, dflt, *labels)
        }

        override fun visitLookupSwitchInsn(
            dflt: Label,
            keys: IntArray,
            labels: Array<out Label>,
        ) {
            begin()
            super.visitLookupSwitchInsn(dflt, keys, labels)
        }

        override fun visitMultiANewArrayInsn(
            descriptor: String,
            numDimensions: Int,
        ) {
            begin()
            super.visitMultiANewArrayInsn(descriptor, numDimensions)
        }

        override fun visitMaxs(
            maxStack: Int,
            maxLocals: Int,
        ) {
            begin()
            super.visitLabel(end)
            super.visitLabel(handler)
            when (frame) {
                FrameKind.EXPANDED -> super.visitFrame(Opcodes.F_NEW, 0, emptyArray(), 1, arrayOf<Any>(THROWABLE))
                FrameKind.COMPRESSED -> super.visitFrame(Opcodes.F_FULL, 0, emptyArray(), 1, arrayOf<Any>(THROWABLE))
                FrameKind.NONE -> Unit
            }
            increment(threwSlot)
            super.visitInsn(Opcodes.ATHROW)
            super.visitMaxs(maxOf(maxStack + MethodProbes.PROBE_STACK, HANDLER_STACK), maxLocals)
        }

        /**
         * Opens the catch range at the first instruction or label of the body. ASM delivers every
         * original `visitTryCatchBlock` before it, and the writer appends handlers in call order,
         * so the handler declared here is the last entry of the exception table.
         */
        private fun begin() {
            if (started) return
            started = true
            super.visitTryCatchBlock(start, end, handler, null)
            super.visitLabel(start)
        }

        private fun increment(slot: Int) {
            if (form.hasField) {
                super.visitFieldInsn(Opcodes.GETSTATIC, owner, ProbeArrayForm.PROBE_ARRAY_FIELD, "[J")
            } else {
                form.load(mv)
            }
            ProbeArrayMembers.push(mv, slot)
            super.visitInsn(Opcodes.DUP2)
            super.visitInsn(Opcodes.LALOAD)
            super.visitInsn(Opcodes.LCONST_1)
            super.visitInsn(Opcodes.LADD)
            super.visitInsn(Opcodes.LASTORE)
        }
    }
}
