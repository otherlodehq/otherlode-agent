package dev.otherlode.instrumentation.endpoints.jaxrs

import dev.otherlode.instrumentation.endpoints.EndpointInstrumentation
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.ClassFileLocator
import net.bytebuddy.jar.asm.ClassWriter
import net.bytebuddy.jar.asm.Opcodes
import net.bytebuddy.pool.TypePool
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URI
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** An ordinary class with a supertype of its own and no JAX-RS annotation anywhere. */
open class GatePlainBase

/** A second plain class, so a loader defines several. */
class GatePlainOne : GatePlainBase()

/** A third plain class. */
class GatePlainTwo : GatePlainBase()

/**
 * Pins the per-loader gate and the per-loader supertype cache of [JaxRsModule].
 *
 * The gate tests ask the module's loader matcher directly. The cache tests describe synthetic
 * classes from a [TypePool] whose locator counts reads, which is the same thing the agent's
 * pipeline does per class. The pipeline tests run the matcher [EndpointInstrumentation.rawMatcher]
 * builds over classes described through a counting loader, as ByteBuddy's pool describes a class on
 * its first load, rather than under the installed `-javaagent`, which another test class uninstalls.
 */
class JaxRsLoaderGateTest {
    private val pathResources = listOf("jakarta/ws/rs/Path.class", "javax/ws/rs/Path.class")

    /** A loader that exposes exactly [visible] as resources and counts every `getResource`. */
    private class ResourceLoader(
        private val visible: Set<String>,
    ) : ClassLoader(null) {
        val lookups = CopyOnWriteArrayList<String>()

        override fun getResource(name: String): URL? {
            lookups += name
            return if (name in visible) URI("file:/$name").toURL() else null
        }
    }

    @Test
    fun `the gate rejects the bootstrap loader and the platform loader without a lookup`() {
        val gate = JaxRsModule().classLoaderMatcher()

        assertFalse(gate.matches(null))
        assertFalse(gate.matches(ClassLoader.getPlatformClassLoader()))
    }

    @Test
    fun `the gate rejects a loader that sees neither JAX-RS Path class`() {
        assertFalse(JaxRsModule().classLoaderMatcher().matches(ResourceLoader(emptySet())))
    }

    @Test
    fun `the gate accepts a loader that sees only the jakarta Path class`() {
        assertTrue(JaxRsModule().classLoaderMatcher().matches(ResourceLoader(setOf("jakarta/ws/rs/Path.class"))))
    }

    @Test
    fun `the gate accepts a loader that sees only the javax Path class`() {
        assertTrue(JaxRsModule().classLoaderMatcher().matches(ResourceLoader(setOf("javax/ws/rs/Path.class"))))
    }

    @Test
    fun `a second query for the same loader makes no resource lookup`() {
        val gate = JaxRsModule().classLoaderMatcher()
        val without = ResourceLoader(emptySet())
        val with = ResourceLoader(setOf("javax/ws/rs/Path.class"))

        repeat(3) {
            gate.matches(without)
            gate.matches(with)
        }

        assertEquals(pathResources, without.lookups.toList())
        assertEquals(listOf(pathResources.first(), pathResources.last()), with.lookups.toList())
    }

    /** Serves [classes] as class files and counts every `getResource` and `getResourceAsStream`. */
    private class ProbingLoader(
        private val classes: Map<String, ByteArray>,
        private val visible: Set<String>,
    ) : ClassLoader(getPlatformClassLoader()) {
        val lookups = CopyOnWriteArrayList<String>()
        val streams = CopyOnWriteArrayList<String>()

        override fun getResource(name: String): URL? {
            lookups += name
            return if (name in visible) URI("file:/$name").toURL() else null
        }

        override fun getResourceAsStream(name: String): InputStream? {
            streams += name
            return classes[name.removeSuffix(".class").replace('/', '.')]?.let(::ByteArrayInputStream)
        }
    }

    private fun plainClassBytes(): Map<String, ByteArray> =
        listOf(GatePlainBase::class.java, GatePlainOne::class.java, GatePlainTwo::class.java).associate { type ->
            type.name to type.classLoader.getResourceAsStream(type.name.replace('.', '/') + ".class")!!.readAllBytes()
        }

    /**
     * Runs the matcher the endpoint pipeline builds for [JaxRsModule] over every class in [classes]
     * as ByteBuddy's pool would on a first load: the class's own bytes first, everything else
     * through [loader].
     */
    private fun matchThroughPipeline(
        classes: Map<String, ByteArray>,
        loader: ClassLoader,
    ): List<Boolean> {
        val matcher = EndpointInstrumentation.rawMatcher(JaxRsModule())
        return classes.map { (name, bytes) ->
            val locator = ClassFileLocator.Compound(ClassFileLocator.Simple.of(name, bytes), ClassFileLocator.ForClassLoader.of(loader))
            val type =
                TypePool.Default
                    .WithLazyResolution(TypePool.CacheProvider.Simple(), locator, TypePool.Default.ReaderMode.FAST)
                    .describe(name)
                    .resolve()
            matcher.matches(type, loader, null, null, null)
        }
    }

    @Test
    fun `a loader without JAX-RS reads no class file for this module`() {
        val classes = plainClassBytes()
        val loader = ProbingLoader(classes, visible = emptySet())

        assertEquals(listOf(false, false, false), matchThroughPipeline(classes, loader))

        assertEquals(emptyList(), loader.streams.filter { it.endsWith(".class") })
        assertEquals(pathResources, loader.lookups.toList())
    }

    @Test
    fun `a loader with JAX-RS still has its class files read, so the check above can fail`() {
        val classes = plainClassBytes()
        val loader = ProbingLoader(classes, visible = setOf("jakarta/ws/rs/Path.class"))

        assertEquals(listOf(false, false, false), matchThroughPipeline(classes, loader))

        assertTrue(loader.streams.any { it == GatePlainBase::class.java.name.replace('.', '/') + ".class" })
    }

    /** Counts how many times each class file is read through [delegate]. */
    private class CountingLocator(
        private val delegate: ClassFileLocator,
    ) : ClassFileLocator {
        val reads = ConcurrentHashMap<String, Int>()

        override fun locate(name: String): ClassFileLocator.Resolution {
            reads.merge(name.replace('.', '/'), 1, Int::plus)
            return delegate.locate(name)
        }

        override fun close() = delegate.close()
    }

    private class SyntheticClasses {
        private val files = LinkedHashMap<String, ByteArray>()
        private val locator by lazy {
            CountingLocator(
                ClassFileLocator.Compound(
                    ClassFileLocator.Simple(files.mapKeys { it.key.replace('/', '.') }),
                    ClassFileLocator.ForClassLoader.ofSystemLoader(),
                ),
            )
        }

        fun define(
            name: String,
            superName: String = "java/lang/Object",
            interfaces: List<String> = emptyList(),
            isInterface: Boolean = false,
            path: Boolean = false,
        ): SyntheticClasses {
            val writer = ClassWriter(0)
            val access = if (isInterface) Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT else Opcodes.ACC_PUBLIC
            writer.visit(Opcodes.V17, access, name, null, superName, interfaces.toTypedArray())
            if (path) writer.visitAnnotation("Ljakarta/ws/rs/Path;", true).also { it.visit("value", "/x") }.visitEnd()
            writer.visitEnd()
            files[name] = writer.toByteArray()
            return this
        }

        fun describe(name: String): TypeDescription =
            TypePool.Default
                .WithLazyResolution(TypePool.CacheProvider.Simple(), locator, TypePool.Default.ReaderMode.FAST)
                .describe(name.replace('/', '.'))
                .resolve()

        fun reads(name: String): Int = locator.reads[name] ?: 0
    }

    @Test
    fun `a supertype shared by two classes is read once per loader`() {
        val classes = SyntheticClasses().define("t/Base").define("t/A", "t/Base").define("t/B", "t/Base")
        val loader = ResourceLoader(emptySet())
        val module = JaxRsModule()

        assertFalse(module.typeMatcher(loader).matches(classes.describe("t/A")))
        assertFalse(module.typeMatcher(loader).matches(classes.describe("t/B")))

        assertEquals(1, classes.reads("t/Base"))
    }

    @Test
    fun `the supertype cache is keyed by loader`() {
        val classes = SyntheticClasses().define("t/Base").define("t/A", "t/Base")
        val module = JaxRsModule()

        module.typeMatcher(ResourceLoader(emptySet())).matches(classes.describe("t/A"))
        module.typeMatcher(ResourceLoader(emptySet())).matches(classes.describe("t/A"))

        assertEquals(2, classes.reads("t/Base"))
    }

    @Test
    fun `the form that is not told the loader caches nothing`() {
        val classes = SyntheticClasses().define("t/Base").define("t/A", "t/Base")
        val module = JaxRsModule()

        module.typeMatcher().matches(classes.describe("t/A"))
        module.typeMatcher().matches(classes.describe("t/A"))

        assertEquals(2, classes.reads("t/Base"))
    }

    @Test
    fun `a Path from an interface still matches and the interface is read once`() {
        val classes =
            SyntheticClasses()
                .define("t/Api", isInterface = true, path = true)
                .define("t/Impl", interfaces = listOf("t/Api"))
                .define("t/Impl2", interfaces = listOf("t/Api"))
        val module = JaxRsModule()
        val loader = ResourceLoader(emptySet())

        assertTrue(module.typeMatcher(loader).matches(classes.describe("t/Impl")))
        assertTrue(module.typeMatcher(loader).matches(classes.describe("t/Impl2")))

        assertEquals(1, classes.reads("t/Api"))
    }

    @Test
    fun `a Path on an abstract base class still matches`() {
        val classes =
            SyntheticClasses()
                .define("t/AbstractBase", path = true)
                .define("t/Concrete", "t/AbstractBase")
        val matcher = JaxRsModule().typeMatcher(ResourceLoader(emptySet()))

        assertTrue(matcher.matches(classes.describe("t/Concrete")))
    }

    @Test
    fun `a diamond of unannotated interfaces terminates and is rejected`() {
        val classes =
            SyntheticClasses()
                .define("t/Top", isInterface = true)
                .define("t/Left", interfaces = listOf("t/Top"), isInterface = true)
                .define("t/Right", interfaces = listOf("t/Top"), isInterface = true)
                .define("t/D", interfaces = listOf("t/Left", "t/Right"))
        val matcher = JaxRsModule().typeMatcher(ResourceLoader(emptySet()))

        assertFalse(matcher.matches(classes.describe("t/D")))
        assertFalse(matcher.matches(classes.describe("t/D")))
    }

    @Test
    fun `a diamond with a Path at the top matches`() {
        val classes =
            SyntheticClasses()
                .define("t/Top", isInterface = true, path = true)
                .define("t/Left", interfaces = listOf("t/Top"), isInterface = true)
                .define("t/Right", interfaces = listOf("t/Top"), isInterface = true)
                .define("t/D", interfaces = listOf("t/Left", "t/Right"))

        assertTrue(JaxRsModule().typeMatcher(ResourceLoader(emptySet())).matches(classes.describe("t/D")))
    }

    @Test
    fun `a cycle in the interface graph terminates, and a Path inside it is still found`() {
        val plain =
            SyntheticClasses()
                .define("t/I1", interfaces = listOf("t/I2"), isInterface = true)
                .define("t/I2", interfaces = listOf("t/I1"), isInterface = true)
                .define("t/C", interfaces = listOf("t/I1"))
        val annotated =
            SyntheticClasses()
                .define("t/I1", interfaces = listOf("t/I2"), isInterface = true)
                .define("t/I2", interfaces = listOf("t/I1"), isInterface = true, path = true)
                .define("t/C", interfaces = listOf("t/I1"))
        val module = JaxRsModule()
        val loader = ResourceLoader(emptySet())

        assertFalse(module.typeMatcher(loader).matches(plain.describe("t/C")))
        assertFalse(module.typeMatcher(loader).matches(plain.describe("t/C")))
        assertTrue(JaxRsModule().typeMatcher(loader).matches(annotated.describe("t/C")))
    }
}
