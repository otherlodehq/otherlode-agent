package dev.otherlode.instrumentation.branch

import dev.otherlode.export.CallEdge
import net.bytebuddy.jar.asm.ClassWriter
import net.bytebuddy.jar.asm.Opcodes
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves that a call into a Scala value class's boxed forwarder or a trait forwarder passes through
 * to the method the forwarder calls (ADR 0041, ADR 0080), over the Scala fixture modules.
 */
class ScalaForwarderCallGraphTest {
    private companion object {
        const val PACKAGE = "com/example/scalatarget"
        const val DOTTED = "com.example.scalatarget"
    }

    private fun lookup(module: String): (String) -> ByteArray? =
        { internalName ->
            File(System.getProperty("otherlode.fixtures.$module.dir"), "$internalName.class").takeIf { it.isFile }?.readBytes()
                ?: loaders.getValue(module).getResourceAsStream("$internalName.class")?.use { it.readBytes() }
        }

    private val loaders =
        listOf("scala2", "scala3").associateWith { module ->
            java.net.URLClassLoader(
                System
                    .getProperty("otherlode.fixtures.$module.classpath")
                    .split(File.pathSeparator)
                    .filter { it.endsWith(".jar") }
                    .map { File(it).toURI().toURL() }
                    .toTypedArray(),
                null,
            )
        }

    /** A class with static methods that call a mixed-in trait method and a value class's boxed method. */
    private fun caller(): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "$PACKAGE/ForwarderCaller", null, "java/lang/Object", null)
        writer
            .visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "viaTrait", "(L$PACKAGE/Greeter;)Ljava/lang/String;", null, null)
            .apply {
                visitCode()
                visitVarInsn(Opcodes.ALOAD, 0)
                visitLdcInsn("x")
                visitMethodInsn(Opcodes.INVOKEVIRTUAL, "$PACKAGE/Greeter", "greet", "(Ljava/lang/String;)Ljava/lang/String;", false)
                visitInsn(Opcodes.ARETURN)
                visitMaxs(0, 0)
                visitEnd()
            }
        writer
            .visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "viaValueClass", "(L$PACKAGE/ValueSyntax\$RichInt;)I", null, null)
            .apply {
                visitCode()
                visitVarInsn(Opcodes.ALOAD, 0)
                visitMethodInsn(Opcodes.INVOKEVIRTUAL, "$PACKAGE/ValueSyntax\$RichInt", "doubled", "()I", false)
                visitInsn(Opcodes.IRETURN)
                visitMaxs(0, 0)
                visitEnd()
            }
        writer
            .visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "viaSpecialised", "(L$PACKAGE/Inc;)I", null, null)
            .apply {
                visitCode()
                visitVarInsn(Opcodes.ALOAD, 0)
                visitInsn(Opcodes.ICONST_1)
                visitMethodInsn(Opcodes.INVOKEVIRTUAL, "$PACKAGE/Inc", "apply", "(I)I", false)
                visitInsn(Opcodes.IRETURN)
                visitMaxs(0, 0)
                visitEnd()
            }
        writer
            .visitMethod(
                Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
                "viaJavaDefault",
                "(L$PACKAGE/ComparingHandler;)Ljava/util/Comparator;",
                null,
                null,
            ).apply {
                visitCode()
                visitVarInsn(Opcodes.ALOAD, 0)
                visitMethodInsn(Opcodes.INVOKEVIRTUAL, "$PACKAGE/ComparingHandler", "reversed", "()Ljava/util/Comparator;", false)
                visitInsn(Opcodes.ARETURN)
                visitMaxs(0, 0)
                visitEnd()
            }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun edges(
        module: String,
        name: String,
        descriptor: String,
    ): List<CallEdge> =
        BranchSiteAnalyzer
            .analyze(caller(), lookup(module), listOf(DOTTED)) { methodName, _ -> methodName != "<clinit>" }
            .callsOf(name, descriptor)

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["scala3", "scala2"])
    fun `a call to a mixed-in trait method reaches the trait's default method`(module: String) {
        val edges = edges(module, "viaTrait", "(L$PACKAGE/Greeter;)Ljava/lang/String;")

        assertTrue(edges.any { it.className == "$DOTTED.Greets" && it.methodName == "greet" }, edges.toString())
        assertEquals(emptyList(), edges.filter { it.methodName == "greet\$" }, "the static entry is no node")
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["scala3", "scala2"])
    fun `a call to a value class's boxed method reaches its extension method`(module: String) {
        val edges = edges(module, "viaValueClass", "(L$PACKAGE/ValueSyntax\$RichInt;)I")

        assertTrue(
            edges.any { it.className == "$DOTTED.ValueSyntax\$RichInt\$" && it.methodName == "doubled\$extension" },
            edges.toString(),
        )
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["scala3", "scala2"])
    fun `a call to a specialised function's apply reaches its specialised twin`(module: String) {
        val edges = edges(module, "viaSpecialised", "(L$PACKAGE/Inc;)I")

        assertTrue(edges.any { it.className == "$DOTTED.Inc" && it.methodName == "apply\$mcII\$sp" }, edges.toString())
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["scala3", "scala2"])
    fun `a call to a forwarder of a Java default method walks through it and names no JDK class`(module: String) {
        val edges = edges(module, "viaJavaDefault", "(L$PACKAGE/ComparingHandler;)Ljava/util/Comparator;")

        assertEquals(emptyList(), edges.filter { it.className.startsWith("java.") }, edges.toString())
    }
}
