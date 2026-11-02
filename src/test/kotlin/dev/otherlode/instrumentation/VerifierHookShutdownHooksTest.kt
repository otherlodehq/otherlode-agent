package dev.otherlode.instrumentation

import dev.otherlode.bootstrap.OtherlodeShutdownHooks
import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import java.lang.instrument.ClassFileTransformer
import java.lang.instrument.Instrumentation
import java.security.ProtectionDomain
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The weave of the real `java.lang.ApplicationShutdownHooks`. It runs in a JVM of its own, with
 * `-XX:+BytecodeVerificationLocal`, because it weaves a boot class: the JVM then verifies the woven
 * class. The hook table is not run here, since that would run the test JVM's own hooks.
 */
class VerifierHookShutdownHooksTest {
    private val instrumentation: Instrumentation = VerifierHookFixtures.instrumentation()

    private fun callsSeam(): Boolean {
        val bytes = currentBytes()
        var calls = false
        ClassReader(bytes).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    name: String?,
                    descriptor: String?,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor =
                    object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitMethodInsn(
                            opcode: Int,
                            owner: String?,
                            name: String?,
                            descriptor: String?,
                            isInterface: Boolean,
                        ) {
                            if (owner == "dev/otherlode/bootstrap/OtherlodeShutdownHooks") calls = true
                        }
                    }
            },
            0,
        )
        return calls
    }

    private fun currentBytes(): ByteArray {
        var seen = ByteArray(0)
        val capture =
            object : ClassFileTransformer {
                override fun transform(
                    loader: ClassLoader?,
                    className: String?,
                    classBeingRedefined: Class<*>?,
                    protectionDomain: ProtectionDomain?,
                    classfileBuffer: ByteArray,
                ): ByteArray? {
                    if (className == "java/lang/ApplicationShutdownHooks") seen = classfileBuffer.clone()
                    return null
                }
            }
        instrumentation.addTransformer(capture, true)
        try {
            instrumentation.retransformClasses(Class.forName("java.lang.ApplicationShutdownHooks", false, null))
        } finally {
            instrumentation.removeTransformer(capture)
        }
        return seen
    }

    @Test
    fun `the real class is woven to call the seam, opens nothing, and is restored by uninstall`() {
        assertFalse(callsSeam(), "the class starts unwoven")
        val hook = ShutdownHooksHook()

        assertTrue(hook.install(instrumentation), "the weave installs on this JVM")

        assertTrue(callsSeam(), "runHooks calls the seam")
        val javaBase = Any::class.java.module
        assertFalse(javaBase.isOpen("java.lang"), "java.lang is open to nobody")
        assertFalse(javaBase.isOpen("java.lang", ShutdownHooksHook::class.java.module))
        val probe = Class.forName("java.lang.ApplicationShutdownHooks", false, null).getDeclaredField("hooks")
        assertFalse(probe.trySetAccessible(), "the agent's module has no deep reflection into the class")
        val added = Thread {}
        Runtime.getRuntime().addShutdownHook(added)
        Runtime.getRuntime().removeShutdownHook(added)
        assertNull(OtherlodeShutdownHooks.hooks(), "adding and removing hooks offers nothing")

        hook.uninstall(instrumentation)

        assertFalse(callsSeam(), "uninstall restores the class")
    }
}
