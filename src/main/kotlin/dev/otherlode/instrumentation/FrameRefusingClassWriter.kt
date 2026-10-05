package dev.otherlode.instrumentation

import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassWriter
import net.bytebuddy.pool.TypePool
import net.bytebuddy.utility.AsmClassReader
import net.bytebuddy.utility.AsmClassWriter
import java.util.concurrent.atomic.AtomicLong

/**
 * The class writer factory the agent's ByteBuddy uses. Every class it writes goes through ASM's own
 * writer, never the Class File API's, and that writer never computes a stack map frame: its
 * `getCommonSuperClass`, the one hook ASM calls to merge two types while computing frames, throws.
 * The frames in a woven class are the ones the received bytes carry plus the ones the branch tier
 * writes itself, so a path that would ask for a merge has recomputed a frame the agent meant to
 * keep, and fails the transform where it would otherwise guess a type.
 *
 * ByteBuddy reaches a class writer only through [AsmClassWriter.Factory], so no other writer exists
 * for the Class File API path to bypass this one.
 */
internal object FrameRefusingClassWriter : AsmClassWriter.Factory {
    /** How often a frame merge was asked for, which is how often a transform was failed by one. */
    val refusals: Long get() = merges.get()

    private val merges = AtomicLong()

    override fun make(flags: Int): AsmClassWriter = AsmClassWriter.ForAsm(Writer(null, flags))

    override fun make(
        flags: Int,
        classReader: AsmClassReader,
    ): AsmClassWriter = AsmClassWriter.ForAsm(Writer(classReader.unwrap(ClassReader::class.java), flags))

    override fun make(
        flags: Int,
        typePool: TypePool,
    ): AsmClassWriter = make(flags)

    override fun make(
        flags: Int,
        classReader: AsmClassReader,
        typePool: TypePool,
    ): AsmClassWriter = make(flags, classReader)

    private class Writer(
        reader: ClassReader?,
        flags: Int,
    ) : ClassWriter(reader, flags) {
        override fun getCommonSuperClass(
            type1: String,
            type2: String,
        ): String {
            merges.incrementAndGet()
            throw IllegalStateException(
                "otherlode: ASM asked to merge $type1 and $type2 while computing a stack map frame; " +
                    "the agent keeps the frames of the bytes it receives and never computes one",
            )
        }
    }
}
