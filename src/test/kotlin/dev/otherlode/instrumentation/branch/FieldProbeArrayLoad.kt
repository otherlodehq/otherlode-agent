package dev.otherlode.instrumentation.branch

import dev.otherlode.instrumentation.ProbeArrayForm
import dev.otherlode.instrumentation.ProbeArrayLoad
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes

internal class FieldProbeArrayLoad(
    private val ownerInternalName: String,
) : ProbeArrayLoad {
    override fun load(methodVisitor: MethodVisitor) {
        methodVisitor.visitFieldInsn(Opcodes.GETSTATIC, ownerInternalName, ProbeArrayForm.PROBE_ARRAY_FIELD, "[J")
    }
}
