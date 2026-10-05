package dev.otherlode.instrumentation

import dev.otherlode.advice.MethodEntryAdvice
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

/**
 * Writes the members a class below version 55 gets so its probes can reach their counts array, and
 * the marker a refused re-weave adds, as ASM events on the class being decorated (ADR 0060). A
 * decoration cannot define a member, so these are written directly.
 *
 * For a [ProbeArrayForm] with a field the class gets:
 *
 * - `public static final synthetic long[] $otherlodeProbeCounts`;
 * - a prelude at the start of its `<clinit>`, or a new `<clinit>` holding only the prelude when it
 *   has none:
 *
 *   ```
 *   ldc          "<class name>"
 *   ldc2_w       <layout hash>
 *   ldc          <probe count>
 *   ldc          <this class>
 *   invokevirtual java/lang/Class.getClassLoader()
 *   invokestatic OtherlodeProbeArrays.resolve(String, long, int, ClassLoader) long[]
 *   putstatic    <this class>.$otherlodeProbeCounts
 *   ```
 *
 *   followed, when the class has a type initializer probe, by an increment of its slot;
 * - for [ProbeArrayForm.Accessor] only, the private static synthetic accessor every probe calls and
 *   the slow path it falls back on while the field is null.
 *
 * The prelude adds no branch and no local, so it needs no stack map frame and leaves every frame of
 * the original `<clinit>` where it was. The class literal is `ldc <class>` from version 49 and
 * `ldc "<name>"` then `Class.forName` below it.
 *
 * The prelude runs ahead of the class's own initializer, so a probed static method called from that
 * initializer finds the field set, and the `<clinit>` probe is counted even when the original body
 * after it throws. The field is still null before the prelude runs, which a supertype's initializer
 * can precede, so probes in a class with the field read it through [ProbeArrayForm.Accessor] and not
 * directly.
 *
 * Every argument of the prelude is a constant, so a retransformation weaves the same instructions.
 */
internal object ProbeArrayMembers {
    /** The wrapper that gives a class woven in [form] its field, prelude and, for an accessor form, its accessors. */
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

    /** The wrapper that adds the marker field of a refused re-weave; see [MethodEntryAdvice.REFUSAL_MARKER_FIELD]. */
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
    private const val FIELD_ACCESS = Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL or Opcodes.ACC_SYNTHETIC
    private const val PRIVATE_SYNTHETIC = Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC or Opcodes.ACC_SYNTHETIC

    private val RESOLVE: MethodDescription.InDefinedShape =
        TypeDescription.ForLoadedType
            .of(OtherlodeProbeArrays::class.java)
            .declaredMethods
            .filter(named<MethodDescription>("resolve"))
            .only

    private fun push(
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
            cv.visitField(FIELD_ACCESS, MethodEntryAdvice.PROBE_ARRAY_FIELD, ARRAY_DESCRIPTOR, null, null)?.visitEnd()
            if (!sawTypeInitializer) {
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
            visitor.visitFieldInsn(Opcodes.PUTSTATIC, owner, MethodEntryAdvice.PROBE_ARRAY_FIELD, ARRAY_DESCRIPTOR)
            if (typeInitializerProbeIndex != null) {
                visitor.visitFieldInsn(Opcodes.GETSTATIC, owner, MethodEntryAdvice.PROBE_ARRAY_FIELD, ARRAY_DESCRIPTOR)
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

        /**
         * The accessor:
         *
         * ```
         * getstatic    <this class>.$otherlodeProbeCounts
         * dup
         * ifnonnull    done
         * pop
         * invokestatic <this class>.$otherlodeProbesResolve()
         * done:
         * areturn
         * ```
         *
         * It does not store the resolved array: a final static field is written only by its class's
         * `<clinit>`, and the array is the one the prelude stores later. The frame at `done` is
         * written from version 50, where the verifier requires one, and left out below it.
         */
        private fun writeAccessor() {
            val visitor = cv.visitMethod(PRIVATE_SYNTHETIC, MethodEntryAdvice.PROBE_ARRAY_ACCESSOR, ARRAY_FACTORY_DESCRIPTOR, null, null)
            val done = Label()
            visitor.visitCode()
            visitor.visitFieldInsn(Opcodes.GETSTATIC, owner, MethodEntryAdvice.PROBE_ARRAY_FIELD, ARRAY_DESCRIPTOR)
            visitor.visitInsn(Opcodes.DUP)
            visitor.visitJumpInsn(Opcodes.IFNONNULL, done)
            visitor.visitInsn(Opcodes.POP)
            visitor.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                owner,
                MethodEntryAdvice.PROBE_ARRAY_SLOW_PATH,
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

        /**
         * The slow path, the prelude's call without its store, so the accessor stays small enough
         * for C1 to inline.
         */
        private fun writeSlowPath() {
            val visitor = cv.visitMethod(PRIVATE_SYNTHETIC, MethodEntryAdvice.PROBE_ARRAY_SLOW_PATH, ARRAY_FACTORY_DESCRIPTOR, null, null)
            visitor.visitCode()
            resolve(visitor)
            visitor.visitInsn(Opcodes.ARETURN)
            visitor.visitMaxs(FILL_STACK, 0)
            visitor.visitEnd()
        }
    }

    private class RefusalMarkerVisitor(
        visitor: ClassVisitor,
    ) : ClassVisitor(Opcodes.ASM9, visitor) {
        override fun visitEnd() {
            cv.visitField(FIELD_ACCESS, MethodEntryAdvice.REFUSAL_MARKER_FIELD, "I", null, null)?.visitEnd()
            super.visitEnd()
        }
    }
}
