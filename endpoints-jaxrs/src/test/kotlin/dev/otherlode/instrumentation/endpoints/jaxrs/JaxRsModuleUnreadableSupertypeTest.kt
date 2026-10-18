package dev.otherlode.instrumentation.endpoints.jaxrs

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
 * The agent's pool resolves a supertype lazily, so a supertype it has no class file for fails when
 * the walk reads it, not when the walk names it. A supertype that cannot be loaded at all means the
 * class being matched can never be defined; one that loads without a reachable class file is read
 * from the loaded class.
 */
class JaxRsModuleUnreadableSupertypeTest {
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
        interfaces: List<String> = emptyList(),
        superName: String = "java/lang/Object",
        isInterface: Boolean = false,
        path: Boolean = false,
    ): ByteArray {
        val writer = ClassWriter(0)
        val access = if (isInterface) Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT else Opcodes.ACC_PUBLIC
        writer.visit(Opcodes.V17, access, name, null, superName, interfaces.toTypedArray())
        if (path) writer.visitAnnotation("Ljakarta/ws/rs/Path;", true).also { it.visit("value", "/x") }.visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun describe(files: Map<String, ByteArray>): (String) -> TypeDescription {
        val locator = ClassFileLocator.Compound(ClassFileLocator.Simple(files), ClassFileLocator.ForClassLoader.ofSystemLoader())
        val pool = TypePool.Default.WithLazyResolution(TypePool.CacheProvider.Simple(), locator, TypePool.Default.ReaderMode.FAST)
        return { name -> pool.describe(name).resolve() }
    }

    @Test
    fun `a supertype that cannot be loaded decides the class as no match without throwing`() {
        val describe = describe(mapOf("t.Resource" to classBytes("t/Resource", superName = "t/Missing")))
        val loader = DefiningLoader(javaClass.classLoader)

        assertFalse(JaxRsModule().typeMatcher(loader).matches(describe("t.Resource")))
    }

    @Test
    fun `a class with its own Path and a supertype that cannot be loaded is still no match`() {
        val describe = describe(mapOf("t.Resource" to classBytes("t/Resource", superName = "t/Missing", path = true)))
        val loader = DefiningLoader(javaClass.classLoader)

        assertFalse(JaxRsModule().typeMatcher(loader).matches(describe("t.Resource")))
    }

    @Test
    fun `a class with its own Path and an unloadable supertype further up is no match`() {
        val files =
            mapOf(
                "t.Resource" to classBytes("t/Resource", superName = "t/Base", path = true),
                "t.Base" to classBytes("t/Base", superName = "t/Missing"),
            )
        val describe = describe(files)
        val loader = DefiningLoader(javaClass.classLoader)

        assertFalse(JaxRsModule().typeMatcher(loader).matches(describe("t.Resource")))
    }

    @Test
    fun `the module stays on after a class it could not define, and still matches the next resource`() {
        val describe =
            describe(
                mapOf(
                    "t.Broken" to classBytes("t/Broken", superName = "t/Missing"),
                    "t.Good" to classBytes("t/Good", path = true),
                ),
            )
        val loader = DefiningLoader(javaClass.classLoader)
        val matcher = EndpointInstrumentation.rawMatcher(JaxRsModule())

        assertFalse(matcher.matches(describe("t.Broken"), loader, null, null, null))
        assertTrue(matcher.matches(describe("t.Good"), loader, null, null, null))
    }

    @Test
    fun `a supertype defined with no class file is read from the loaded class`() {
        val loader = DefiningLoader(javaClass.classLoader)
        loader.define("t.Api", classBytes("t/Api", isInterface = true, path = true))
        val describe = describe(mapOf("t.Resource" to classBytes("t/Resource", interfaces = listOf("t/Api"))))

        assertTrue(JaxRsModule().typeMatcher(loader).matches(describe("t.Resource")))
    }

    @Test
    fun `a supertype defined with no class file and no route annotation is no match`() {
        val loader = DefiningLoader(javaClass.classLoader)
        loader.define("t.Plain", classBytes("t/Plain"))
        val describe = describe(mapOf("t.Resource" to classBytes("t/Resource", superName = "t/Plain")))

        assertFalse(JaxRsModule().typeMatcher(loader).matches(describe("t.Resource")))
    }
}
