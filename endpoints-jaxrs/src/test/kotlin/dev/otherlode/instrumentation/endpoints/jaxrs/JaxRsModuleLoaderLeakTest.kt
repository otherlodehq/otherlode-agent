package dev.otherlode.instrumentation.endpoints.jaxrs

import dev.otherlode.instrumentation.endpoints.EndpointInstrumentation
import net.bytebuddy.agent.builder.AgentBuilder
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
class JaxRsModuleLoaderLeakTest {
    private class DefiningLoader(
        parent: ClassLoader,
    ) : ClassLoader(parent)

    private fun resourceBytes(): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "t/Resource", null, "java/lang/Object", null)
        writer.visitAnnotation("Ljakarta/ws/rs/Path;", true).also { it.visit("value", "/x") }.visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun describeResource(): TypeDescription {
        val locator =
            ClassFileLocator.Compound(
                ClassFileLocator.Simple(mapOf("t.Resource" to resourceBytes())),
                ClassFileLocator.ForClassLoader.ofSystemLoader(),
            )
        return TypePool.Default
            .WithLazyResolution(TypePool.CacheProvider.Simple(), locator, TypePool.Default.ReaderMode.FAST)
            .describe("t.Resource")
            .resolve()
    }

    private fun matchInFreshLoader(matcher: AgentBuilder.RawMatcher): WeakReference<ClassLoader> {
        val loader = DefiningLoader(javaClass.classLoader)
        assertTrue(matcher.matches(describeResource(), loader, null, null, null), "the resource matched")
        return WeakReference(loader)
    }

    @Test
    fun `a loader the matcher matched a resource in is collected once nothing else holds it`() {
        val matcher = EndpointInstrumentation.rawMatcher(JaxRsModule())
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
