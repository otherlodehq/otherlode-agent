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

/**
 * Rewrites the `jsr` and `ret` subroutines of a class file below version 51 into straight-line
 * code, which is what every later reader of the class has to see. A subroutine is how javac 1.4.1
 * and older, and ecj, compile a `finally`; the JVM forbids them from version 51 (JVMS 4.9.1). The branch tier makes ASM compute frames, which
 * cannot be done over `jsr`/`ret`, so the class is inlined before the analysis counts its branch
 * sites and before the rewrite adds probes. Both read the same inlined instructions, so the sites
 * the analysis numbers are the sites the rewrite finds.
 *
 * Two entry points cover the two readers. [inline] rewrites bytes, for the analysis and the pairing
 * of a class file with the bytes another transformer left. [wrapper] is the visitor for the rewrite
 * ByteBuddy runs over the received bytes. Each runs ASM's [JSRInlinerAdapter] on every method, and
 * the adapter is deterministic, so a class inlined by either path yields the same instruction
 * sequence, except that [inline] serialises the result, and a conditional jump over 32 KB in an
 * inlined copy is widened there and not on the visitor path.
 */
internal object SubroutineInliner {
    /** The first class-file version whose methods may not contain `jsr` or `ret` (JVMS 4.9.1). */
    const val FIRST_SUBROUTINE_FREE_VERSION = 51

    /** Whether a class file of [majorVersion] may contain `jsr` or `ret`. */
    fun mayHaveSubroutines(majorVersion: Int): Boolean = majorVersion in 1 until FIRST_SUBROUTINE_FREE_VERSION

    /**
     * [classFile] with its subroutines inlined, or [classFile] itself, the same array, when its
     * version is 51 or above or it has none, so a class that needs nothing keeps its bytes.
     */
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

    /**
     * The visitor wrapper that inlines each method's subroutines in the class ByteBuddy rewrites,
     * when the class file's version is below 51 and a no-op otherwise. ByteBuddy's `Compound` wraps
     * in the order the wrappers were added to the builder, so the first added is nearest the class
     * writer and the last added is the first to see a method read from the class. This wrapper
     * therefore has to be added after every Advice and branch wrapper, so that none of them sees a
     * `jsr`.
     */
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
