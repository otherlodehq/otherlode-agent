package dev.otherlode.instrumentation.endpoints.servlet

import net.bytebuddy.jar.asm.ClassWriter
import net.bytebuddy.jar.asm.Opcodes
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServletModuleClassFileCheckTest {
    private val module = ServletModule()

    private fun classWithMethod(
        name: String,
        methodName: String,
        descriptor: String,
    ): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null)
        val method = writer.visitMethod(Opcodes.ACC_PUBLIC, methodName, descriptor, null, null)
        method.visitCode()
        method.visitInsn(Opcodes.RETURN)
        method.visitMaxs(0, 3)
        method.visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }

    @Test
    fun `a class declaring a javax service method may match`() {
        val bytes = classWithMethod("a/Javax", "service", "(Ljavax/servlet/ServletRequest;Ljavax/servlet/ServletResponse;)V")
        assertTrue(module.classFileMayMatch(bytes))
    }

    @Test
    fun `a class declaring a jakarta service method may match`() {
        val bytes = classWithMethod("a/Jakarta", "service", "(Ljakarta/servlet/ServletRequest;Ljakarta/servlet/ServletResponse;)V")
        assertTrue(module.classFileMayMatch(bytes))
    }

    @Test
    fun `a service method with another return type may match`() {
        val bytes = classWithMethod("a/Other", "service", "(Ljakarta/servlet/ServletRequest;Ljakarta/servlet/ServletResponse;)I")
        assertTrue(module.classFileMayMatch(bytes))
    }

    @Test
    fun `a class with no such method cannot match`() {
        val bytes = classWithMethod("a/Plain", "run", "(Ljava/lang/String;)V")
        assertFalse(module.classFileMayMatch(bytes))
    }

    @Test
    fun `a compiled class that does not mention the servlet response cannot match`() {
        val bytes = Any::class.java.getResourceAsStream("Object.class")!!.use { it.readAllBytes() }
        assertFalse(module.classFileMayMatch(bytes))
    }

    @Test
    fun `an empty class file cannot match`() {
        assertFalse(module.classFileMayMatch(ByteArray(0)))
    }
}
