package dev.otherlode.instrumentation

import dev.otherlode.advice.MethodEntryAdvice
import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.ConstantDynamic
import net.bytebuddy.jar.asm.FieldVisitor
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import java.io.File
import java.nio.file.Files

/** What a test can read from a class file about how, or whether, this agent wove it. */
internal object WovenBytes {
    /** Whether [bytes] declare a field called [name]. */
    fun declaresField(
        bytes: ByteArray,
        name: String = MethodEntryAdvice.PROBE_ARRAY_FIELD,
    ): Boolean {
        var found = false
        ClassReader(bytes).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitField(
                    access: Int,
                    fieldName: String,
                    descriptor: String,
                    signature: String?,
                    value: Any?,
                ): FieldVisitor? {
                    if (fieldName == name) found = true
                    return null
                }
            },
            ClassReader.SKIP_FRAMES,
        )
        return found
    }

    /** The names of the methods [bytes] declare. */
    fun methodNames(bytes: ByteArray): Set<String> {
        val names = HashSet<String>()
        ClassReader(bytes).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor? {
                    names += name
                    return null
                }
            },
            ClassReader.SKIP_CODE,
        )
        return names
    }

    /** Whether [bytes] load a dynamic constant bootstrapped from the probe holder anywhere in their code. */
    fun loadsProbeConstant(bytes: ByteArray): Boolean {
        var found = false
        ClassReader(bytes).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor =
                    object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitLdcInsn(value: Any?) {
                            if (value is ConstantDynamic && value.bootstrapMethod.owner == HOLDER &&
                                value.bootstrapMethod.name == "probeArray"
                            ) {
                                found = true
                            }
                        }
                    }
            },
            ClassReader.SKIP_FRAMES,
        )
        return found
    }

    /** Whether this agent wove [bytes], in either form. */
    fun isWoven(bytes: ByteArray): Boolean = declaresField(bytes) || loadsProbeConstant(bytes)

    /** The class-file major version of [bytes]. */
    fun majorVersion(bytes: ByteArray): Int = ProbeArrayForm.majorVersionOf(bytes)

    private const val HOLDER = "dev/otherlode/bootstrap/OtherlodeProbeArrays"
}

/**
 * The fixtures under `com.example.target` with their class-file version set to 52 (Java 8), the
 * newest version at which a woven class keeps its probe field and reaches it through an accessor.
 * The version byte is the only change, so every fixture that uses nothing newer than Java 8's
 * semantics loads and runs as it does at the toolchain's own version. The classes are served from a
 * directory, so the agent reads the class file it is woven from through the loader as it would for
 * any fixture.
 */
internal object LegacyFixtures {
    const val MAJOR_VERSION = 52

    private val sourceDirs = listOf(File("build/classes/java/test"), File("build/classes/kotlin/test"))

    /** [bytes] with the class-file major version set to [MAJOR_VERSION]. */
    fun downgraded(bytes: ByteArray): ByteArray =
        bytes.copyOf().also {
            it[6] = (MAJOR_VERSION shr 8).toByte()
            it[7] = MAJOR_VERSION.toByte()
        }

    /** Copies [source] to [destination] with its version set to [MAJOR_VERSION]. */
    fun copyDowngraded(
        source: File,
        destination: File,
    ) {
        destination.parentFile.mkdirs()
        destination.writeBytes(downgraded(source.readBytes()))
    }

    /** A directory holding every `com.example.target` fixture class, downgraded; written once per JVM. */
    val directory: File by lazy {
        val target = Files.createTempDirectory("otherlode-legacy-fixtures").toFile()
        target.deleteOnExit()
        for (source in sourceDirs) {
            val root = File(source, "com/example/target")
            if (!root.isDirectory) continue
            root.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.forEach {
                copyDowngraded(it, File(target, it.relativeTo(source).path))
            }
        }
        target
    }

    /** A loader that defines the downgraded fixtures itself, after instrumentation is installed. */
    fun loader(parent: ClassLoader): ClassLoader = FixtureClassLoader(arrayOf(directory.toURI().toURL()), parent)
}
