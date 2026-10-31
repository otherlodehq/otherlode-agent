package dev.otherlode.instrumentation.endpoints.servlet

import dev.otherlode.instrumentation.endpoints.EndpointInstrumentation
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.ClassFileLocator
import net.bytebuddy.jar.asm.ClassWriter
import net.bytebuddy.jar.asm.Opcodes
import net.bytebuddy.pool.TypePool
import java.lang.ref.Reference
import java.lang.ref.WeakReference
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A retired class loader must stay collectible after the module's matcher has matched a class in it. */
class ServletModuleLoaderLeakTest {
    private class DefiningLoader(
        parent: ClassLoader,
    ) : ClassLoader(parent)

    private fun servletBytes(): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "t/Svc", null, "java/lang/Object", arrayOf("jakarta/servlet/Servlet"))
        val method =
            writer.visitMethod(
                Opcodes.ACC_PUBLIC,
                "service",
                "(Ljakarta/servlet/ServletRequest;Ljakarta/servlet/ServletResponse;)V",
                null,
                null,
            )
        method.visitCode()
        method.visitInsn(Opcodes.RETURN)
        method.visitMaxs(0, 3)
        method.visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun describeSvc(): TypeDescription {
        val locator =
            ClassFileLocator.Compound(
                ClassFileLocator.Simple(mapOf("t.Svc" to servletBytes())),
                ClassFileLocator.ForClassLoader.ofSystemLoader(),
            )
        return TypePool.Default
            .WithLazyResolution(TypePool.CacheProvider.Simple(), locator, TypePool.Default.ReaderMode.FAST)
            .describe("t.Svc")
            .resolve()
    }

    private fun matchInFreshLoader(matcher: net.bytebuddy.agent.builder.AgentBuilder.RawMatcher): WeakReference<ClassLoader> {
        val loader = DefiningLoader(javaClass.classLoader)
        assertTrue(matcher.matches(describeSvc(), loader, null, null, null), "the servlet matched")
        return WeakReference(loader)
    }

    @Test
    fun `a loader the matcher matched a servlet in is collected once nothing else holds it`() {
        val matcher = EndpointInstrumentation.rawMatcher(ServletModule())
        val reference = matchInFreshLoader(matcher)

        repeat(40) {
            if (reference.get() != null) {
                System.gc()
                Thread.sleep(50)
            }
        }
        Reference.reachabilityFence(matcher)
        assertNull(reference.get(), "the class loader is still reachable")
    }
}
