package dev.otherlode.instrumentation

import dev.otherlode.bootstrap.OtherlodeProbeArrays
import net.bytebuddy.asm.AsmVisitorWrapper
import net.bytebuddy.description.field.FieldDescription
import net.bytebuddy.description.field.FieldList
import net.bytebuddy.description.method.MethodDescription
import net.bytebuddy.description.method.MethodList
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.implementation.Implementation
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.Label
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import net.bytebuddy.jar.asm.Type
import net.bytebuddy.matcher.ElementMatchers.named
import net.bytebuddy.pool.TypePool

internal object ProbeArrayMembers {
    fun wrapper(
        form: ProbeArrayForm,
        className: String,
        layoutHash: Long,
        probeCount: Int,
        typeInitializerProbeIndex: Int?,
    ): AsmVisitorWrapper {
        require(form.hasField) { "otherlode: $form adds no member" }
        return wrapping { MembersVisitor(it, form, className, layoutHash, probeCount, typeInitializerProbeIndex) }
    }

    fun refusalMarkerWrapper(): AsmVisitorWrapper = wrapping { RefusalMarkerVisitor(it) }

    private fun wrapping(visitor: (ClassVisitor) -> ClassVisitor): AsmVisitorWrapper =
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
            ): ClassVisitor = visitor(classVisitor)
        }

    private const val TYPE_INITIALIZER = "<clinit>"
    private const val VOID_DESCRIPTOR = "()V"
    private const val ARRAY_DESCRIPTOR = "[J"
    private const val ARRAY_FACTORY_DESCRIPTOR = "()[J"
    private const val CLASS = "java/lang/Class"
    private const val CLASS_LITERAL_VERSION = 49
    private const val STACK_FRAME_VERSION = 50
    private const val FILL_STACK = 5
    private const val INCREMENT_STACK = 6
    private const val ACCESSOR_STACK = 2
    private const val INTERFACE_FIELD_ACCESS = Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL or Opcodes.ACC_SYNTHETIC
    private const val CLASS_FIELD_ACCESS = Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC or Opcodes.ACC_SYNTHETIC
    private const val MARKER_FIELD_ACCESS = Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL or Opcodes.ACC_SYNTHETIC
    private const val PRIVATE_SYNTHETIC = Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC or Opcodes.ACC_SYNTHETIC

    private val RESOLVE: MethodDescription.InDefinedShape =
        TypeDescription.ForLoadedType
            .of(OtherlodeProbeArrays::class.java)
            .declaredMethods
            .filter(named<MethodDescription>("resolve"))
            .only

    fun push(
        visitor: MethodVisitor,
        value: Int,
    ) {
        when (value) {
            in -1..5 -> visitor.visitInsn(Opcodes.ICONST_0 + value)
            in Byte.MIN_VALUE..Byte.MAX_VALUE -> visitor.visitIntInsn(Opcodes.BIPUSH, value)
            in Short.MIN_VALUE..Short.MAX_VALUE -> visitor.visitIntInsn(Opcodes.SIPUSH, value)
            else -> visitor.visitLdcInsn(value)
        }
    }

    private fun pushLong(
        visitor: MethodVisitor,
        value: Long,
    ) {
        when (value) {
            0L -> visitor.visitInsn(Opcodes.LCONST_0)
            1L -> visitor.visitInsn(Opcodes.LCONST_1)
            else -> visitor.visitLdcInsn(value)
        }
    }

    private class MembersVisitor(
        visitor: ClassVisitor,
        private val form: ProbeArrayForm,
        private val className: String,
        private val layoutHash: Long,
        private val probeCount: Int,
        private val typeInitializerProbeIndex: Int?,
    ) : ClassVisitor(Opcodes.ASM9, visitor) {
        private lateinit var owner: String
        private var isInterface = false
        private var majorVersion = 0
        private var sawTypeInitializer = false

        private val storesInSlowPath get() = !isInterface && !sawTypeInitializer

        private val fieldAccess
            get() =
                when {
                    isInterface -> INTERFACE_FIELD_ACCESS
                    sawTypeInitializer -> CLASS_FIELD_ACCESS or Opcodes.ACC_FINAL
                    else -> CLASS_FIELD_ACCESS
                }

        private val preludeStack get() = if (typeInitializerProbeIndex == null) FILL_STACK else INCREMENT_STACK

        override fun visit(
            version: Int,
            access: Int,
            name: String,
            signature: String?,
            superName: String?,
            interfaces: Array<out String>?,
        ) {
            owner = name
            isInterface = access and Opcodes.ACC_INTERFACE != 0
            majorVersion = version and 0xffff
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
            sawTypeInitializer = true
            return object : MethodVisitor(Opcodes.ASM9, visitor) {
                override fun visitCode() {
                    super.visitCode()
                    prelude(mv)
                }

                override fun visitMaxs(
                    maxStack: Int,
                    maxLocals: Int,
                ) = super.visitMaxs(maxOf(maxStack, preludeStack), maxLocals)
            }
        }

        override fun visitEnd() {
            cv.visitField(fieldAccess, ProbeArrayForm.PROBE_ARRAY_FIELD, ARRAY_DESCRIPTOR, null, null)?.visitEnd()
            if (!sawTypeInitializer && isInterface) {
                val initializer = cv.visitMethod(Opcodes.ACC_STATIC, TYPE_INITIALIZER, VOID_DESCRIPTOR, null, null)
                initializer.visitCode()
                prelude(initializer)
                initializer.visitInsn(Opcodes.RETURN)
                initializer.visitMaxs(preludeStack, 0)
                initializer.visitEnd()
            }
            if (form is ProbeArrayForm.Accessor) {
                writeAccessor()
                writeSlowPath()
            }
            super.visitEnd()
        }

        private fun prelude(visitor: MethodVisitor) {
            resolve(visitor)
            visitor.visitFieldInsn(Opcodes.PUTSTATIC, owner, ProbeArrayForm.PROBE_ARRAY_FIELD, ARRAY_DESCRIPTOR)
            if (typeInitializerProbeIndex != null) {
                visitor.visitFieldInsn(Opcodes.GETSTATIC, owner, ProbeArrayForm.PROBE_ARRAY_FIELD, ARRAY_DESCRIPTOR)
                push(visitor, typeInitializerProbeIndex)
                visitor.visitInsn(Opcodes.DUP2)
                visitor.visitInsn(Opcodes.LALOAD)
                visitor.visitInsn(Opcodes.LCONST_1)
                visitor.visitInsn(Opcodes.LADD)
                visitor.visitInsn(Opcodes.LASTORE)
            }
        }

        private fun resolve(visitor: MethodVisitor) {
            visitor.visitLdcInsn(className)
            pushLong(visitor, layoutHash)
            push(visitor, probeCount)
            if (majorVersion >= CLASS_LITERAL_VERSION) {
                visitor.visitLdcInsn(Type.getObjectType(owner))
            } else {
                visitor.visitLdcInsn(className)
                visitor.visitMethodInsn(Opcodes.INVOKESTATIC, CLASS, "forName", "(Ljava/lang/String;)Ljava/lang/Class;", false)
            }
            visitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, CLASS, "getClassLoader", "()Ljava/lang/ClassLoader;", false)
            visitor.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                RESOLVE.declaringType.asErasure().internalName,
                RESOLVE.internalName,
                RESOLVE.descriptor,
                false,
            )
        }

        private fun writeAccessor() {
            val visitor = cv.visitMethod(PRIVATE_SYNTHETIC, ProbeArrayForm.PROBE_ARRAY_ACCESSOR, ARRAY_FACTORY_DESCRIPTOR, null, null)
            val done = Label()
            visitor.visitCode()
            visitor.visitFieldInsn(Opcodes.GETSTATIC, owner, ProbeArrayForm.PROBE_ARRAY_FIELD, ARRAY_DESCRIPTOR)
            visitor.visitInsn(Opcodes.DUP)
            visitor.visitJumpInsn(Opcodes.IFNONNULL, done)
            visitor.visitInsn(Opcodes.POP)
            visitor.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                owner,
                ProbeArrayForm.PROBE_ARRAY_SLOW_PATH,
                ARRAY_FACTORY_DESCRIPTOR,
                isInterface,
            )
            visitor.visitLabel(done)
            if (majorVersion >= STACK_FRAME_VERSION) {
                visitor.visitFrame(Opcodes.F_NEW, 0, emptyArray(), 1, arrayOf<Any>(ARRAY_DESCRIPTOR))
            }
            visitor.visitInsn(Opcodes.ARETURN)
            visitor.visitMaxs(ACCESSOR_STACK, 0)
            visitor.visitEnd()
        }

        private fun writeSlowPath() {
            val visitor = cv.visitMethod(PRIVATE_SYNTHETIC, ProbeArrayForm.PROBE_ARRAY_SLOW_PATH, ARRAY_FACTORY_DESCRIPTOR, null, null)
            visitor.visitCode()
            resolve(visitor)
            if (storesInSlowPath) {
                visitor.visitInsn(Opcodes.DUP)
                visitor.visitFieldInsn(Opcodes.PUTSTATIC, owner, ProbeArrayForm.PROBE_ARRAY_FIELD, ARRAY_DESCRIPTOR)
            }
            visitor.visitInsn(Opcodes.ARETURN)
            visitor.visitMaxs(FILL_STACK, 0)
            visitor.visitEnd()
        }
    }

    private class RefusalMarkerVisitor(
        visitor: ClassVisitor,
    ) : ClassVisitor(Opcodes.ASM9, visitor) {
        override fun visitEnd() {
            cv.visitField(MARKER_FIELD_ACCESS, ProbeArrayForm.REFUSAL_MARKER_FIELD, "I", null, null)?.visitEnd()
            super.visitEnd()
        }
    }
}
