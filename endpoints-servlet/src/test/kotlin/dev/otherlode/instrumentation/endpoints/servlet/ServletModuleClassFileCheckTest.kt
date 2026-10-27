package dev.otherlode.instrumentation.endpoints.servlet

import net.bytebuddy.jar.asm.ClassWriter
import net.bytebuddy.jar.asm.Opcodes
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
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

    private val marker = "/servlet/ServletResponse;)".toByteArray(Charsets.US_ASCII)

    private fun naiveContains(haystack: ByteArray): Boolean {
        for (start in 0..haystack.size - marker.size) {
            if (marker.indices.all { haystack[start + it] == marker[it] }) return true
        }
        return false
    }

    private fun noise(
        random: Random,
        size: Int,
    ): ByteArray = ByteArray(size) { random.nextInt(256).toByte() }

    private fun textNoise(
        random: Random,
        size: Int,
    ): ByteArray {
        val alphabet = "/servletRponsc;)Ljakartx".toByteArray(Charsets.US_ASCII)
        return ByteArray(size) { alphabet[random.nextInt(alphabet.size)] }
    }

    @Test
    fun `the class file check agrees with a naive search on generated input`() {
        val random = Random(20261026)
        val partials =
            listOf(
                "/servlet/ServletResponse".toByteArray(Charsets.US_ASCII),
                "/servlet/ServletResponse;".toByteArray(Charsets.US_ASCII),
                "/servlet/servlet/servlet/".toByteArray(Charsets.US_ASCII),
                "/servlet/ServletRespons;)".toByteArray(Charsets.US_ASCII),
                "ervlet/ServletResponse;)".toByteArray(Charsets.US_ASCII),
                byteArrayOf(0x80.toByte(), 0xff.toByte(), 0xc3.toByte()),
            )
        var matches = 0
        repeat(5000) { round ->
            val size = random.nextInt(0, 120)
            val base = if (round % 2 == 0) noise(random, size) else textNoise(random, size)
            val parts = mutableListOf(base)
            when (random.nextInt(4)) {
                0 -> parts.add(0, marker)
                1 -> parts.add(marker)
                2 -> parts.add(random.nextInt(parts.size + 1), marker)
                else -> Unit
            }
            repeat(random.nextInt(3)) { parts.add(random.nextInt(parts.size + 1), partials[random.nextInt(partials.size)]) }
            val bytes = parts.fold(ByteArray(0)) { acc, part -> acc + part }
            val expected = naiveContains(bytes)
            if (expected) matches++
            assertEquals(expected, module.classFileMayMatch(bytes), "input ${bytes.joinToString(",")}")
        }
        assertTrue(matches > 500, "matches $matches")
    }

    @Test
    fun `the class file check agrees with a naive search on short and edge input`() {
        for (size in 0..marker.size + 1) {
            for (offset in 0..1) {
                val bytes = ByteArray(size) { marker[(it + offset) % marker.size] }
                assertEquals(naiveContains(bytes), module.classFileMayMatch(bytes))
            }
        }
        assertTrue(module.classFileMayMatch(marker))
        assertFalse(module.classFileMayMatch(marker.copyOfRange(0, marker.size - 1)))
        assertFalse(module.classFileMayMatch(marker.copyOfRange(1, marker.size)))
        assertTrue(module.classFileMayMatch(ByteArray(300) { 0xff.toByte() } + marker))
        assertFalse(module.classFileMayMatch(ByteArray(300) { 0xff.toByte() }))
    }
}
