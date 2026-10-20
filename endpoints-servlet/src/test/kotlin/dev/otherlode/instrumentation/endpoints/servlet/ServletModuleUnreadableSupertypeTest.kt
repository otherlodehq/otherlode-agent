package dev.otherlode.instrumentation.endpoints.servlet

import dev.otherlode.instrumentation.endpoints.EndpointInstrumentation
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.ClassFileLocator
import net.bytebuddy.jar.asm.ClassWriter
import net.bytebuddy.jar.asm.Opcodes
import net.bytebuddy.pool.TypePool
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The agent's pool resolves a supertype lazily, so a class declaring `service` whose superclass has
 * no class file fails the matcher when it reads the hierarchy. A superclass that cannot be loaded
 * means the class can never be defined; one that loads without a reachable class file is read from
 * the loaded class.
 */
class ServletModuleUnreadableSupertypeTest {
    private class DefiningLoader(
        parent: ClassLoader,
    ) : ClassLoader(parent) {
        fun define(
            name: String,
            bytes: ByteArray,
        ): Class<*> = defineClass(name, bytes, 0, bytes.size)
    }

    private fun classBytes(
        name: String,
        superName: String = "java/lang/Object",
        interfaces: List<String> = emptyList(),
        declaresService: Boolean = false,
        isAbstract: Boolean = false,
    ): ByteArray {
        val writer = ClassWriter(0)
        val access = Opcodes.ACC_PUBLIC or if (isAbstract) Opcodes.ACC_ABSTRACT else 0
        writer.visit(Opcodes.V17, access, name, null, superName, interfaces.toTypedArray())
        if (declaresService) {
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
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun describe(files: Map<String, ByteArray>): (String) -> TypeDescription {
        val locator = ClassFileLocator.Compound(ClassFileLocator.Simple(files), ClassFileLocator.ForClassLoader.ofSystemLoader())
        val pool = TypePool.Default.WithLazyResolution(TypePool.CacheProvider.Simple(), locator, TypePool.Default.ReaderMode.FAST)
        return { name -> pool.describe(name).resolve() }
    }

    @Test
    fun `a class declaring service whose superclass cannot be loaded is no match and the module stays on`() {
        val describe =
            describe(
                mapOf(
                    "t.Broken" to classBytes("t/Broken", superName = "t/Missing", declaresService = true),
                    "t.Good" to classBytes("t/Good", interfaces = listOf("jakarta/servlet/Servlet"), declaresService = true),
                ),
            )
        val loader = DefiningLoader(javaClass.classLoader)
        val matcher = EndpointInstrumentation.rawMatcher(ServletModule())

        assertFalse(matcher.matches(describe("t.Broken"), loader, null, null, null))
        assertTrue(matcher.matches(describe("t.Good"), loader, null, null, null), "the module is still on")
    }

    @Test
    fun `a class declaring service whose superclass is a servlet defined with no class file is a match`() {
        val loader = DefiningLoader(javaClass.classLoader)
        loader.define("t.Base", classBytes("t/Base", interfaces = listOf("jakarta/servlet/Servlet"), isAbstract = true))
        val describe = describe(mapOf("t.Svc" to classBytes("t/Svc", superName = "t/Base", declaresService = true)))

        assertTrue(EndpointInstrumentation.rawMatcher(ServletModule()).matches(describe("t.Svc"), loader, null, null, null))
    }

    @Test
    fun `a class declaring service whose superclass is defined with no class file and is no servlet is no match`() {
        val loader = DefiningLoader(javaClass.classLoader)
        loader.define("t.Plain", classBytes("t/Plain"))
        val describe = describe(mapOf("t.Svc" to classBytes("t/Svc", superName = "t/Plain", declaresService = true)))

        assertFalse(EndpointInstrumentation.rawMatcher(ServletModule()).matches(describe("t.Svc"), loader, null, null, null))
    }
}
