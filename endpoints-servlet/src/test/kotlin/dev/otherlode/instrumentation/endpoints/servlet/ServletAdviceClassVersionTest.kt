package dev.otherlode.instrumentation.endpoints.servlet

import dev.otherlode.endpoints.servlet.ServletServiceAdvice
import net.bytebuddy.ByteBuddy
import net.bytebuddy.asm.Advice
import net.bytebuddy.description.method.MethodDescription
import net.bytebuddy.dynamic.ClassFileLocator
import net.bytebuddy.dynamic.scaffold.TypeValidation
import net.bytebuddy.jar.asm.ClassWriter
import net.bytebuddy.jar.asm.Opcodes
import net.bytebuddy.matcher.ElementMatchers.named
import net.bytebuddy.pool.TypePool
import kotlin.test.Test
import kotlin.test.assertEquals

private class ServletApiStubs : ClassFileLocator {
    override fun locate(name: String): ClassFileLocator.Resolution {
        if (!name.startsWith("javax.servlet.")) return ClassFileLocator.Resolution.Illegal(name)
        val writer = ClassWriter(0)
        writer.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT,
            name.replace('.', '/'),
            null,
            "java/lang/Object",
            null,
        )
        writer.visitEnd()
        return ClassFileLocator.Resolution.Explicit(writer.toByteArray())
    }

    override fun close() = Unit
}

/**
 * The advice is inlined into a servlet class at that class's own file version. This weaves it into
 * servlets of old versions and loads and initialises them against stub servlet types, so the
 * verifier reads the woven code. A constant the version cannot hold, or a class literal the old
 * verifier rejects, fails it.
 */
class ServletAdviceClassVersionTest {
    private fun servlet(version: Int): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(version, Opcodes.ACC_PUBLIC, "OldServlet", null, "java/lang/Object", null)
        val method =
            writer.visitMethod(
                Opcodes.ACC_PUBLIC,
                "service",
                "(Ljavax/servlet/ServletRequest;Ljavax/servlet/ServletResponse;)V",
                null,
                null,
            )
        method.visitCode()
        method.visitInsn(Opcodes.RETURN)
        method.visitMaxs(0, 0)
        method.visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }

    @Test
    fun `the advice is woven into servlets of class file versions 48 to 50 that still define`() {
        for (version in listOf(Opcodes.V1_4, Opcodes.V1_5, Opcodes.V1_6)) {
            val locator =
                ClassFileLocator.Compound(
                    ClassFileLocator.Simple.of("OldServlet", servlet(version)),
                    ClassFileLocator.ForClassLoader.of(ServletServiceAdvice::class.java.classLoader),
                    ServletApiStubs(),
                )
            val pool = TypePool.Default.of(locator)
            val woven =
                ByteBuddy()
                    .with(TypeValidation.DISABLED)
                    .decorate<Any>(pool.describe("OldServlet").resolve(), locator)
                    .visit(
                        Advice
                            .to(
                                pool.describe(ServletServiceAdvice::class.java.name).resolve(),
                                locator,
                            ).on(named<MethodDescription>("service")),
                    ).make()
                    .bytes
            val stubs = ServletApiStubs()
            val loader =
                object : ClassLoader(null) {
                    override fun findClass(name: String): Class<*> {
                        val bytes = if (name == "OldServlet") woven else stubs.locate(name).takeIf { it.isResolved }?.resolve()
                        return bytes?.let { defineClass(name, it, 0, it.size) } ?: throw ClassNotFoundException(name)
                    }
                }
            assertEquals("OldServlet", Class.forName("OldServlet", true, loader).name, "version $version")
        }
    }
}
