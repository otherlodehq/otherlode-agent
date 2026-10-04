package dev.otherlode.instrumentation

import dev.otherlode.advice.MethodEntryAdvice
import dev.otherlode.bootstrap.OtherlodeProbeArrays
import net.bytebuddy.description.method.MethodDescription
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.implementation.Implementation
import net.bytebuddy.implementation.bytecode.ByteCodeAppender
import net.bytebuddy.implementation.bytecode.StackManipulation
import net.bytebuddy.implementation.bytecode.constant.ClassConstant
import net.bytebuddy.implementation.bytecode.constant.IntegerConstant
import net.bytebuddy.implementation.bytecode.constant.JavaConstantValue
import net.bytebuddy.implementation.bytecode.constant.LongConstant
import net.bytebuddy.implementation.bytecode.constant.TextConstant
import net.bytebuddy.implementation.bytecode.member.MethodInvocation
import net.bytebuddy.jar.asm.Label
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import net.bytebuddy.matcher.ElementMatchers.named
import net.bytebuddy.matcher.ElementMatchers.takesArguments
import net.bytebuddy.utility.JavaConstant

/** Loads a woven class's counts array onto the operand stack; see [ProbeArrayForm]. */
fun interface ProbeArrayLoad {
    /** Emits the instructions that leave the class's `long[]` on the stack: one slot, no local needed. */
    fun load(methodVisitor: MethodVisitor)
}

/**
 * How the probes of one woven class reach its counts array, chosen from the class-file version of
 * the bytes being woven. The version does not change across a class's re-weaves, so a retransformation
 * produces the form the class was first woven with and the JVM never sees a member added or removed.
 *
 * A class initializes its supertypes first, and a supertype's initializer can run the class's code
 * before the class's own `<clinit>` has run. A field `<clinit>` fills is null for that window, so a
 * probe cannot read it unguarded.
 *
 * - [DynamicConstant], from version 55: every probe loads the array with `ldc` of a dynamic
 *   constant bootstrapped by [OtherlodeProbeArrays.probeArray]. Nothing is added to the class.
 * - [Accessor], below 55: the class keeps the field and its `<clinit>` prelude, and every probe
 *   calls a private static synthetic accessor that returns the field or, while it is null, the
 *   array [OtherlodeProbeArrays.resolve] hands out.
 * - [PreludeOnly], an interface below version 52: it cannot have a private method and has no code
 *   outside its `<clinit>`, so only the prelude exists.
 */
internal sealed class ProbeArrayForm : ProbeArrayLoad {
    /** Whether the class gets the `$otherlodeProbeCounts` field and the `<clinit>` prelude that fills it. */
    abstract val hasField: Boolean

    /** The load as a [StackManipulation], for ByteBuddy's `Advice` to inline. */
    fun asStackManipulation(): StackManipulation =
        object : StackManipulation.AbstractBase() {
            override fun apply(
                methodVisitor: MethodVisitor,
                implementationContext: Implementation.Context,
            ): StackManipulation.Size {
                load(methodVisitor)
                return StackManipulation.Size(1, 1)
            }
        }

    /** A dynamic constant bootstrapped from the holder; the class gets no field, prelude or method. */
    class DynamicConstant(
        layoutHash: Long,
        probeCount: Int,
    ) : ProbeArrayForm() {
        private val constant: Any =
            JavaConstant.Dynamic
                .bootstrap(CONSTANT_NAME, BOOTSTRAP, layoutHash, probeCount)
                .accept(JavaConstantValue.Visitor.INSTANCE)

        override val hasField: Boolean get() = false

        override fun load(methodVisitor: MethodVisitor) = methodVisitor.visitLdcInsn(constant)

        private companion object {
            const val CONSTANT_NAME = "otherlodeProbes"
            val BOOTSTRAP: MethodDescription.InDefinedShape =
                TypeDescription.ForLoadedType
                    .of(OtherlodeProbeArrays::class.java)
                    .declaredMethods
                    .filter(named<MethodDescription>("probeArray"))
                    .only
        }
    }

    /** A call to the class's own accessor, [MethodEntryAdvice.PROBE_ARRAY_ACCESSOR]. */
    class Accessor(
        private val ownerInternalName: String,
        private val ownerIsInterface: Boolean,
        private val className: String,
        private val layoutHash: Long,
        private val probeCount: Int,
    ) : ProbeArrayForm() {
        override val hasField: Boolean get() = true

        override fun load(methodVisitor: MethodVisitor) =
            methodVisitor.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                ownerInternalName,
                MethodEntryAdvice.PROBE_ARRAY_ACCESSOR,
                ARRAY_FACTORY_DESCRIPTOR,
                ownerIsInterface,
            )

        /**
         * The accessor's body:
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
         * It does not store the resolved array: a final static field is written only by its
         * class's `<clinit>`, and the array is the one the prelude stores later.
         */
        fun accessorBody(): ByteCodeAppender =
            ByteCodeAppender { methodVisitor, implementationContext, instrumentedMethod ->
                val owner = implementationContext.instrumentedType
                val done = Label()
                methodVisitor.visitFieldInsn(Opcodes.GETSTATIC, owner.internalName, MethodEntryAdvice.PROBE_ARRAY_FIELD, "[J")
                methodVisitor.visitInsn(Opcodes.DUP)
                methodVisitor.visitJumpInsn(Opcodes.IFNONNULL, done)
                methodVisitor.visitInsn(Opcodes.POP)
                methodVisitor.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    owner.internalName,
                    MethodEntryAdvice.PROBE_ARRAY_SLOW_PATH,
                    ARRAY_FACTORY_DESCRIPTOR,
                    owner.isInterface,
                )
                methodVisitor.visitLabel(done)
                methodVisitor.visitFrame(Opcodes.F_NEW, 0, emptyArray(), 1, arrayOf<Any>("[J"))
                methodVisitor.visitInsn(Opcodes.ARETURN)
                ByteCodeAppender.Size(2, instrumentedMethod.stackSize)
            }

        /**
         * The slow path's body, the prelude's call without its store:
         *
         * ```
         * ldc          "<class name>"
         * ldc2_w       <layout hash>
         * ldc          <probe count>
         * ldc          <this class>
         * invokevirtual java/lang/Class.getClassLoader()
         * invokestatic OtherlodeProbeArrays.resolve(String, long, int, ClassLoader) long[]
         * areturn
         * ```
         *
         * A method of its own so the accessor stays small enough for C1 to inline.
         */
        fun slowPathBody(): ByteCodeAppender =
            ByteCodeAppender { methodVisitor, implementationContext, instrumentedMethod ->
                val size =
                    StackManipulation
                        .Compound(
                            TextConstant(className),
                            LongConstant.forValue(layoutHash),
                            IntegerConstant.forValue(probeCount),
                            ClassConstant.of(implementationContext.instrumentedType),
                            MethodInvocation.invoke(GET_CLASS_LOADER),
                            MethodInvocation.invoke(RESOLVE),
                        ).apply(methodVisitor, implementationContext)
                methodVisitor.visitInsn(Opcodes.ARETURN)
                ByteCodeAppender.Size(size.maximalSize, instrumentedMethod.stackSize)
            }
    }

    /** No probe site outside `<clinit>`: the prelude fills the field and increments the `<clinit>` slot. */
    data object PreludeOnly : ProbeArrayForm() {
        override val hasField: Boolean get() = true

        override fun load(methodVisitor: MethodVisitor): Unit =
            throw IllegalStateException("otherlode: an interface below class-file version 52 has no probe site outside its <clinit>")
    }

    companion object {
        /** First class-file major version whose constant pool may hold a dynamic constant (Java 11). */
        const val DYNAMIC_CONSTANT_VERSION = 55

        /** First class-file major version whose interfaces may declare private methods (Java 8). */
        const val INTERFACE_PRIVATE_METHOD_VERSION = 52

        /** Stand-in when no bytes are readable at all: the accessor form needs nothing past Java 8. */
        private const val UNKNOWN_VERSION = INTERFACE_PRIVATE_METHOD_VERSION

        private const val ARRAY_FACTORY_DESCRIPTOR = "()[J"

        private val GET_CLASS_LOADER: MethodDescription.InDefinedShape =
            TypeDescription.ForLoadedType
                .of(Class::class.java)
                .declaredMethods
                .filter(named<MethodDescription>("getClassLoader").and(takesArguments(0)))
                .only
        private val RESOLVE: MethodDescription.InDefinedShape =
            TypeDescription.ForLoadedType
                .of(OtherlodeProbeArrays::class.java)
                .declaredMethods
                .filter(named<MethodDescription>("resolve"))
                .only

        /** The major version in [classFile]'s header, or [UNKNOWN_VERSION] when there is no header to read. */
        fun majorVersionOf(classFile: ByteArray?): Int =
            if (classFile != null && classFile.size >= 8) {
                ((classFile[6].toInt() and 0xff) shl 8) or (classFile[7].toInt() and 0xff)
            } else {
                UNKNOWN_VERSION
            }

        /**
         * Whether a class woven at [wovenVersion] can be woven again in the same form from bytes at
         * [receivedVersion]: a form chosen for one version may be illegal at the other, a dynamic
         * constant below 55 or a private accessor on an interface below 52.
         */
        fun sameForm(
            wovenVersion: Int,
            receivedVersion: Int,
            isInterface: Boolean,
        ): Boolean = kindOf(wovenVersion, isInterface) == kindOf(receivedVersion, isInterface)

        private fun kindOf(
            majorVersion: Int,
            isInterface: Boolean,
        ): Int =
            when {
                majorVersion >= DYNAMIC_CONSTANT_VERSION -> 2
                isInterface && majorVersion < INTERFACE_PRIVATE_METHOD_VERSION -> 0
                else -> 1
            }

        /** The form for [type], a class of [majorVersion] whose registered layout is [layoutHash] with [probeCount] probes. */
        fun of(
            majorVersion: Int,
            type: TypeDescription,
            layoutHash: Long,
            probeCount: Int,
        ): ProbeArrayForm =
            when {
                majorVersion >= DYNAMIC_CONSTANT_VERSION -> DynamicConstant(layoutHash, probeCount)
                type.isInterface && majorVersion < INTERFACE_PRIVATE_METHOD_VERSION -> PreludeOnly
                else -> Accessor(type.internalName, type.isInterface, type.name, layoutHash, probeCount)
            }
    }
}
