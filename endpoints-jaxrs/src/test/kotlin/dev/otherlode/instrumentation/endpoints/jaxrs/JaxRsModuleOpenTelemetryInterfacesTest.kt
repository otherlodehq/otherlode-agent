package dev.otherlode.instrumentation.endpoints.jaxrs

import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.ClassFileLocator
import net.bytebuddy.jar.asm.ClassWriter
import net.bytebuddy.jar.asm.Opcodes
import net.bytebuddy.pool.TypePool
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * OpenTelemetry's agent rewrites a framework class to implement interfaces it generates in
 * memory, and injects helper classes into an application loader from its own loader. No class file
 * for either can be read as a resource, so a pool cannot resolve their supertypes.
 */
class JaxRsModuleOpenTelemetryInterfacesTest {
    private val generated = "io/opentelemetry/javaagent/bootstrap/field/VirtualFieldAccessor\$jakarta\$servlet\$Servlet\$Factory"

    private class Classes {
        private val files = LinkedHashMap<String, ByteArray>()

        fun define(
            name: String,
            interfaces: List<String> = emptyList(),
            isInterface: Boolean = false,
            path: Boolean = false,
        ): Classes {
            val writer = ClassWriter(0)
            val access = if (isInterface) Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT else Opcodes.ACC_PUBLIC
            writer.visit(Opcodes.V17, access, name, null, "java/lang/Object", interfaces.toTypedArray())
            if (path) writer.visitAnnotation("Ljakarta/ws/rs/Path;", true).also { it.visit("value", "/x") }.visitEnd()
            writer.visitEnd()
            files[name.replace('/', '.')] = writer.toByteArray()
            return this
        }

        fun describe(name: String): TypeDescription {
            val locator = ClassFileLocator.Compound(ClassFileLocator.Simple(files), ClassFileLocator.ForClassLoader.ofSystemLoader())
            return TypePool.Default
                .WithLazyResolution(TypePool.CacheProvider.Simple(), locator, TypePool.Default.ReaderMode.FAST)
                .describe(name.replace('/', '.'))
                .resolve()
        }
    }

    private fun matches(type: TypeDescription): Boolean = JaxRsModule().typeMatcher(ClassLoader.getSystemClassLoader()).matches(type)

    @Test
    fun `a class carrying only OpenTelemetry's generated interface is decided as no match`() {
        val classes = Classes().define("t/Dispatcher", interfaces = listOf(generated))

        assertFalse(matches(classes.describe("t/Dispatcher")))
    }

    @Test
    fun `a class carrying the generated interface still matches through a real supertype's Path`() {
        val classes =
            Classes()
                .define("t/Api", isInterface = true, path = true)
                .define("t/Resource", interfaces = listOf(generated, "t/Api"))

        assertTrue(matches(classes.describe("t/Resource")))
    }

    @Test
    fun `a helper class OpenTelemetry injected is decided as no match without reading its supertypes`() {
        val helper = "io/opentelemetry/javaagent/shaded/instrumentation/servlet/v5_0/internal/Servlet5Accessor"
        val accessor = "io/opentelemetry/javaagent/shaded/instrumentation/servlet/ServletAccessor"
        val classes = Classes().define(helper, interfaces = listOf(accessor))

        assertFalse(matches(classes.describe(helper)))
    }

    @Test
    fun `an unresolvable interface that OpenTelemetry did not generate still fails the match`() {
        val classes = Classes().define("t/Resource", interfaces = listOf("com/example/Missing"))

        assertFailsWith<TypePool.Resolution.NoSuchTypeException> { matches(classes.describe("t/Resource")) }
    }
}
