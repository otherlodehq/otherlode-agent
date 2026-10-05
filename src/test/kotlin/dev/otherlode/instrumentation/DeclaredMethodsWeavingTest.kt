package dev.otherlode.instrumentation

import dev.otherlode.benchmark.HotPathWeaver
import dev.otherlode.export.ProbeKind
import dev.otherlode.export.ResourceAttributes
import dev.otherlode.registry.ProbeRegistry
import net.bytebuddy.jar.asm.ClassWriter
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.URLClassLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins that the method tier weaves the methods a class declares, whatever its supertypes declare. The
 * tier builds ByteBuddy with the declared-methods graph, so two methods that differ only in return type
 * stay two methods, and a class whose signatures the default graph cannot erase still weaves.
 */
class DeclaredMethodsWeavingTest {
    private companion object {
        const val PACKAGE = "com.example.generated"
        val RESOURCE = ResourceAttributes("test", null, "instance-1", null, "run-1")
    }

    private val registry = ProbeRegistry()
    private val transformer = HotPathWeaver.offlineTransformer(listOf(PACKAGE), registry)

    private class InMemoryLoader(
        parent: ClassLoader,
        private val classFiles: Map<String, ByteArray>,
    ) : ClassLoader(parent) {
        val defined = HashMap<String, ByteArray>()

        override fun loadClass(
            name: String,
            resolve: Boolean,
        ): Class<*> {
            if (name !in classFiles) return super.loadClass(name, resolve)
            synchronized(getClassLoadingLock(name)) {
                val bytes = defined[name] ?: classFiles.getValue(name)
                return findLoadedClass(name) ?: defineClass(name, bytes, 0, bytes.size)
            }
        }

        override fun getResourceAsStream(name: String): InputStream? {
            val className = name.removeSuffix(".class").replace('/', '.')
            return classFiles[className]?.let { ByteArrayInputStream(it) } ?: super.getResourceAsStream(name)
        }
    }

    private fun writer(
        name: String,
        superName: String,
        access: Int,
        signature: String? = null,
    ): ClassWriter {
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS or ClassWriter.COMPUTE_FRAMES)
        cw.visit(Opcodes.V17, access, name, signature, superName, null)
        return cw
    }

    private fun ClassWriter.constructor(superName: String) {
        val mv = visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
        mv.visitCode()
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false)
        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    private fun ClassWriter.returning(
        name: String,
        descriptor: String,
        constant: String,
    ) {
        val mv: MethodVisitor = visitMethod(Opcodes.ACC_PUBLIC, name, descriptor, null, null)
        mv.visitCode()
        mv.visitLdcInsn(constant)
        mv.visitInsn(Opcodes.ARETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    private fun weave(
        loaderFiles: Map<String, ByteArray>,
        className: String,
    ): InMemoryLoader {
        val loader = InMemoryLoader(javaClass.classLoader, loaderFiles)
        val woven =
            assertNotNull(
                transformer.transform(loader, className.replace('.', '/'), null, null, loaderFiles.getValue(className)),
                "$className was woven",
            )
        loader.defined[className] = woven
        return loader
    }

    private fun hits(
        className: String,
        methodName: String,
    ): Map<String, Long> {
        val probes =
            registry
                .manifest(RESOURCE)
                .probes
                .filter { it.className == className && it.methodName == methodName && it.kind == ProbeKind.METHOD }
        val deltas =
            registry
                .computeDeltaBatch(RESOURCE)
                .batch.deltas
                .associate { (it.classId to it.probeIndex) to it.hitsTotal }
        return probes.associate { it.methodDescriptor to (deltas[it.classId to it.probeIndex] ?: 0L) }
    }

    @Test
    fun `methods that differ only in return type are each counted`() {
        val name = "$PACKAGE.ReturnOverloads"
        val internal = name.replace('.', '/')
        val cw = writer(internal, "java/lang/Object", Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER)
        cw.constructor("java/lang/Object")
        cw.returning("m", "()Ljava/lang/String;", "s")
        cw.returning("m", "()Ljava/lang/Object;", "o")
        cw.returning("m", "()Ljava/lang/CharSequence;", "c")
        cw.visitEnd()

        val loader = weave(mapOf(name to cw.toByteArray()), name)
        val type = Class.forName(name, true, loader)
        val instance = type.getDeclaredConstructor().newInstance()
        for (returnType in listOf(String::class.java, Any::class.java, CharSequence::class.java)) {
            val method = type.declaredMethods.single { it.name == "m" && it.returnType == returnType }
            repeat(2) { method.invoke(instance) }
        }

        assertEquals(
            mapOf(
                "()Ljava/lang/String;" to 2L,
                "()Ljava/lang/Object;" to 2L,
                "()Ljava/lang/CharSequence;" to 2L,
            ),
            hits(name, "m"),
        )
    }

    @Test
    fun `scala's map view classes, whose wildcard signatures the default graph cannot erase, are woven`() {
        val classpath =
            checkNotNull(System.getProperty("otherlode.codesize.classpath.scala.fixtures-scala3")) { "no scala classpath" }
        val urls = classpath.split(File.pathSeparator).filter { it.isNotEmpty() }.map { File(it).toURI().toURL() }
        URLClassLoader(urls.toTypedArray(), ClassLoader.getPlatformClassLoader()).use { shared ->
            val names =
                urls
                    .map { File(it.toURI()) }
                    .filter { it.name.startsWith("scala-library") }
                    .flatMap { jar ->
                        java.util.zip
                            .ZipFile(jar)
                            .use { zip -> zip.entries().toList().map { it.name } }
                    }.filter { it.startsWith("scala/collection/MapView") && it.endsWith(".class") }
                    .map { it.removeSuffix(".class") }
            assertTrue(names.isNotEmpty(), "no MapView classes on the classpath")
            val scalaRegistry = ProbeRegistry()
            val scalaTransformer = HotPathWeaver.offlineTransformer(listOf("scala.collection"), scalaRegistry)
            val failures =
                names.mapNotNull { internal ->
                    val original = checkNotNull(shared.getResourceAsStream("$internal.class")).use { it.readBytes() }
                    val failure = runCatching { scalaTransformer.transform(shared, internal, null, null, original) }.exceptionOrNull()
                    failure?.let { "$internal: ${generateSequence(it) { t -> t.cause }.joinToString(" <- ") { t -> t.message.orEmpty() }}" }
                }
            assertTrue(failures.isEmpty(), "classes that failed to weave: $failures")
        }
    }
}
