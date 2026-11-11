package dev.otherlode.instrumentation.branch

import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.ClassWriter
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import java.io.ByteArrayOutputStream
import java.io.File

object BrokenScalaFixtures {
    val BROKEN = setOf("hashCode", "equals", "copy")

    fun copyWith(
        target: File,
        tooling: String?,
        keepRealTasty: Boolean = false,
    ): File {
        ScalaFixtures.outputDir("scala3").copyRecursively(target, overwrite = true)
        val cc = File(target, "com/example/scalatarget/Cc.class")
        cc.writeBytes(withNop(cc.readBytes(), BROKEN))
        val tasty = File(target, "com/example/scalatarget/Cc.tasty")
        when {
            keepRealTasty -> Unit
            tooling == null -> tasty.delete()
            else -> tasty.writeBytes(tastyHeader(tooling))
        }
        return target
    }

    fun tastyHeader(tooling: String): ByteArray {
        val out = ByteArrayOutputStream()

        fun nat(value: Int) {
            val groups =
                generateSequence(value) { it shr 7 }
                    .takeWhile { it > 0 }
                    .map { it and 0x7f }
                    .toList()
                    .ifEmpty { listOf(0) }
            groups.reversed().forEachIndexed { index, group -> out.write(if (index == groups.lastIndex) group or 0x80 else group) }
        }
        out.write(byteArrayOf(0x5C, 0xA1.toByte(), 0xAB.toByte(), 0x1F))
        nat(28)
        nat(3)
        nat(0)
        val bytes = tooling.toByteArray()
        nat(bytes.size)
        out.write(bytes)
        out.write(ByteArray(16))
        return out.toByteArray()
    }

    private fun withNop(
        classBytes: ByteArray,
        names: Set<String>,
    ): ByteArray {
        val writer = ClassWriter(0)
        val visitor =
            object : ClassVisitor(Opcodes.ASM9, writer) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor {
                    val delegate = super.visitMethod(access, name, descriptor, signature, exceptions)
                    if (name !in names) return delegate
                    return object : MethodVisitor(Opcodes.ASM9, delegate) {
                        override fun visitCode() {
                            super.visitCode()
                            super.visitInsn(Opcodes.NOP)
                        }
                    }
                }
            }
        ClassReader(classBytes).accept(visitor, 0)
        return writer.toByteArray()
    }
}
