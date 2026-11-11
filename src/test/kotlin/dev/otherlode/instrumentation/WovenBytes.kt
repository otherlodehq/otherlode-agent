package dev.otherlode.instrumentation

import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.ConstantDynamic
import net.bytebuddy.jar.asm.FieldVisitor
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import java.io.File
import java.nio.file.Files

internal object WovenBytes {
    fun declaresField(
        bytes: ByteArray,
        name: String = ProbeArrayForm.PROBE_ARRAY_FIELD,
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

    fun isWoven(bytes: ByteArray): Boolean = declaresField(bytes) || loadsProbeConstant(bytes)

    fun majorVersion(bytes: ByteArray): Int = ProbeArrayForm.majorVersionOf(bytes)

    private const val HOLDER = "dev/otherlode/bootstrap/OtherlodeProbeArrays"
}

internal object LegacyFixtures {
    const val MAJOR_VERSION = 52

    private val sourceDirs = listOf(File("build/classes/java/test"), File("build/classes/kotlin/test"))

    fun downgraded(bytes: ByteArray): ByteArray =
        bytes.copyOf().also {
            it[6] = (MAJOR_VERSION shr 8).toByte()
            it[7] = MAJOR_VERSION.toByte()
        }

    fun copyDowngraded(
        source: File,
        destination: File,
    ) {
        destination.parentFile.mkdirs()
        destination.writeBytes(downgraded(source.readBytes()))
    }

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

    fun loader(parent: ClassLoader): ClassLoader = FixtureClassLoader(arrayOf(directory.toURI().toURL()), parent)
}
