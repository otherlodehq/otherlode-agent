package dev.otherlode.instrumentation

import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.ClassFileLocator
import net.bytebuddy.pool.TypePool
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SupertypeGuardTest {
    private val absent = AbsentTypeFixtures.ABSENT_PREFIX
    private val guard = SupertypeGuard(ClassFileByteCache())

    /** Serves [classFiles], keyed by binary name, as resources and nothing else from its own parent. */
    private class ServingLoader(
        private val classFiles: Map<String, ByteArray>,
    ) : ClassLoader(null) {
        override fun getResourceAsStream(name: String): InputStream? =
            classFiles[name.removeSuffix(".class").replace('/', '.')]?.let { ByteArrayInputStream(it) }
    }

    private fun internal(simpleName: String) = "com/example/guard/$simpleName"

    private fun loaderOf(vararg classes: Pair<String, ByteArray>) = ServingLoader(classes.associate { (n, b) -> n.replace('/', '.') to b })

    private fun descriptionOf(
        loader: ClassLoader,
        internalName: String,
    ): TypeDescription =
        TypePool.Default.WithLazyResolution
            .of(ClassFileLocator.ForClassLoader.of(loader))
            .describe(internalName.replace('/', '.'))
            .resolve()

    private fun missingFor(
        loader: ClassLoader,
        internalName: String,
    ): String? = guard.missingSupertype(descriptionOf(loader, internalName), loader)

    @Test
    fun `a class whose superclass is absent is refused`() {
        val orphan = internal("Orphan")
        val loader = loaderOf(orphan to AbsentTypeFixtures.subtype(orphan, absent + "Parent"))

        assertEquals("com.example.absent.Parent", missingFor(loader, orphan))
    }

    @Test
    fun `a class whose interface is absent is refused`() {
        val implementor = internal("Implementor")
        val loader = loaderOf(implementor to AbsentTypeFixtures.subtype(implementor, "java/lang/Object", absent + "Contract"))

        assertEquals("com.example.absent.Contract", missingFor(loader, implementor))
    }

    @Test
    fun `a class whose grand-superclass is absent is refused`() {
        val middle = internal("Middle")
        val leaf = internal("Leaf")
        val loader =
            loaderOf(
                middle to AbsentTypeFixtures.subtype(middle, absent + "Root"),
                leaf to AbsentTypeFixtures.subtype(leaf, middle),
            )

        assertEquals("com.example.absent.Root", missingFor(loader, leaf))
    }

    @Test
    fun `a class whose superclass implements an absent interface is refused`() {
        val parent = internal("Parent")
        val child = internal("Child")
        val loader =
            loaderOf(
                parent to AbsentTypeFixtures.subtype(parent, "java/lang/Object", absent + "Contract"),
                child to AbsentTypeFixtures.subtype(child, parent),
            )

        assertEquals("com.example.absent.Contract", missingFor(loader, child))
    }

    @Test
    fun `a class whose supertypes are all present is not refused`() {
        val parent = internal("Parent")
        val child = internal("Child")
        val loader =
            loaderOf(
                parent to AbsentTypeFixtures.subtype(parent, "java/lang/Object", "java/lang/Runnable"),
                child to AbsentTypeFixtures.subtype(child, parent, "java/util/function/IntUnaryOperator"),
            )

        assertNull(missingFor(loader, child))
    }

    @Test
    fun `a supertype cycle ends the walk`() {
        val first = internal("First")
        val second = internal("Second")
        val loader =
            loaderOf(
                first to AbsentTypeFixtures.subtype(first, second),
                second to AbsentTypeFixtures.subtype(second, first),
            )

        assertNull(missingFor(loader, first))
    }

    @Test
    fun `a supertype the loader defines without serving its class file is missing`() {
        val child = internal("Child")
        val loader = loaderOf(child to AbsentTypeFixtures.subtype(child, internal("MemoryParent")))

        assertEquals("com.example.guard.MemoryParent", missingFor(loader, child))
    }

    @Test
    fun `a loader that serves no class files leaves every supertype outside java missing and the java ones present`() {
        val child = internal("Child")
        val bytes = AbsentTypeFixtures.subtype(child, internal("Parent"), "java/lang/Runnable")
        val onlyJava = AbsentTypeFixtures.subtype(child, "java/lang/Object", "java/lang/Runnable")

        fun describe(classFile: ByteArray) =
            TypePool.Default.WithLazyResolution
                .of(ClassFileLocator.Simple.of(child.replace('/', '.'), classFile))
                .describe(child.replace('/', '.'))
                .resolve()

        assertEquals("com.example.guard.Parent", guard.missingSupertype(describe(bytes), loaderOf()))
        assertNull(guard.missingSupertype(describe(onlyJava), loaderOf()))
    }

    @Test
    fun `a supertype whose class file does not parse is missing`() {
        val child = internal("Child")
        val broken = internal("Broken")
        val loader =
            loaderOf(
                child to AbsentTypeFixtures.subtype(child, broken),
                broken to byteArrayOf(1, 2, 3),
            )

        assertEquals("com.example.guard.Broken", missingFor(loader, child))
    }
}
