package dev.otherlode.instrumentation.branch

import net.bytebuddy.jar.asm.Opcodes

/**
 * Identifies the bytecode jump instructions this tier tracks: every comparison with exactly two
 * outcomes, taken or fell through. `GOTO` and `JSR` are unconditional, and this tier does not track them.
 */
object ConditionalJump {
    fun isTracked(opcode: Int): Boolean =
        opcode in Opcodes.IFEQ..Opcodes.IF_ACMPNE || opcode == Opcodes.IFNULL || opcode == Opcodes.IFNONNULL
}
