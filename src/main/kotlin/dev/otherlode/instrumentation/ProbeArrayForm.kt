package dev.otherlode.instrumentation

import dev.otherlode.bootstrap.OtherlodeProbeArrays
import net.bytebuddy.description.method.MethodDescription
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.implementation.bytecode.constant.JavaConstantValue
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import net.bytebuddy.matcher.ElementMatchers.named
import net.bytebuddy.utility.JavaConstant

/** Loads a woven class's counts array onto the operand stack. */
fun interface ProbeArrayLoad {
    /** Emits the instructions that leave the class's `long[]` on the stack. They take one slot and need no local. */
    fun load(methodVisitor: MethodVisitor)
}

internal sealed class ProbeArrayForm : ProbeArrayLoad {
    abstract val hasField: Boolean

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

    class Accessor(
        private val ownerInternalName: String,
        private val ownerIsInterface: Boolean,
    ) : ProbeArrayForm() {
        override val hasField: Boolean get() = true

        override fun load(methodVisitor: MethodVisitor) =
            methodVisitor.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                ownerInternalName,
                ProbeArrayForm.PROBE_ARRAY_ACCESSOR,
                ARRAY_FACTORY_DESCRIPTOR,
                ownerIsInterface,
            )
    }

    data object PreludeOnly : ProbeArrayForm() {
        override val hasField: Boolean get() = true

        override fun load(methodVisitor: MethodVisitor): Unit =
            throw IllegalStateException("otherlode: an interface below class-file version 52 has no probe site outside its <clinit>")
    }

    companion object {
        const val PROBE_ARRAY_FIELD = "\$otherlodeProbeCounts"

        const val PROBE_ARRAY_ACCESSOR = "\$otherlodeProbes"

        const val PROBE_ARRAY_SLOW_PATH = "\$otherlodeProbesResolve"

        const val REFUSAL_MARKER_FIELD = "\$otherlodeRefused"

        const val DYNAMIC_CONSTANT_VERSION = 55

        const val INTERFACE_PRIVATE_METHOD_VERSION = 52

        private const val UNKNOWN_VERSION = INTERFACE_PRIVATE_METHOD_VERSION

        private const val ARRAY_FACTORY_DESCRIPTOR = "()[J"

        fun majorVersionOf(classFile: ByteArray?): Int =
            if (classFile != null && classFile.size >= 8) {
                ((classFile[6].toInt() and 0xff) shl 8) or (classFile[7].toInt() and 0xff)
            } else {
                UNKNOWN_VERSION
            }

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

        fun of(
            majorVersion: Int,
            type: TypeDescription,
            layoutHash: Long,
            probeCount: Int,
        ): ProbeArrayForm =
            when {
                majorVersion >= DYNAMIC_CONSTANT_VERSION -> DynamicConstant(layoutHash, probeCount)
                type.isInterface && majorVersion < INTERFACE_PRIVATE_METHOD_VERSION -> PreludeOnly
                else -> Accessor(type.internalName, type.isInterface)
            }
    }
}
