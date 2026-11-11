package dev.otherlode.instrumentation

import dev.otherlode.export.GeneratedBy
import dev.otherlode.export.UnreadShape
import dev.otherlode.instrumentation.branch.BranchSiteAnalyzer
import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.Opcodes
import java.io.File

object CompilerFixtures {
    val kotlincVersions: List<String> get() = listProperty("otherlode.fixtures.kotlinc.versions")

    val kotlincSerializationKeys: List<String> get() = listProperty("otherlode.fixtures.kotlincSerialization.keys")

    val javacVersions: List<String> get() = listProperty("otherlode.fixtures.javac.versions")

    val kotlincSuspendsSource: File get() = File(requiredProperty("otherlode.fixtures.kotlinc.suspendsSource"))

    val javacPatternVersions: List<String> get() = javacVersions.filter { it.toInt() >= 21 }

    val scalacVersions: List<String> get() = listProperty("otherlode.fixtures.scalac.versions")

    fun kotlincDefaultsToJvmDefaultEnable(version: String): Boolean {
        val (major, minor) = version.split(".").map { it.takeWhile(Char::isDigit).toInt() }
        return major > 2 || (major == 2 && minor >= 2)
    }

    private const val KOTLIN_PACKAGE = "com/example/target/kotlinc"
    private const val JAVA_PACKAGE = "com/example/target/javac"
    private const val SCALA_PACKAGE = "com/example/scalatarget"

    fun kotlinc(version: String): Build = Build(File(requiredProperty("otherlode.fixtures.kotlinc.$version.dir")), KOTLIN_PACKAGE)

    fun kotlincMixed(version: String): File = File(requiredProperty("otherlode.fixtures.kotlincMixed.$version.dir"))

    fun kotlincSerialization(key: String): Build =
        Build(File(requiredProperty("otherlode.fixtures.kotlincSerialization.$key.dir")), KOTLIN_PACKAGE)

    fun javac(version: String): Build = Build(File(requiredProperty("otherlode.fixtures.javac.$version.dir")), JAVA_PACKAGE)

    fun scalac(version: String): Build =
        Build(
            File(requiredProperty("otherlode.fixtures.scalac.$version.dir")),
            SCALA_PACKAGE,
            listOf("com.example.scalatarget"),
        )

    internal fun scalaLibrary(version: String): List<File> =
        requiredProperty("otherlode.fixtures.${if (version.startsWith("3.")) "scala3" else "scala2"}.classpath")
            .split(File.pathSeparator)
            .filter { it.endsWith(".jar") }
            .map(::File)

    fun scalaBaseline(version: String): Build =
        Build(
            File(requiredProperty("otherlode.fixtures.${if (version.startsWith("3.")) "scala3" else "scala2"}.dir")),
            SCALA_PACKAGE,
            listOf("com.example.scalatarget"),
        )

    fun scalacVersionOutput(version: String): String = File(requiredProperty("otherlode.fixtures.scalac.$version.versionFile")).readText()

    private fun requiredProperty(name: String): String =
        System.getProperty(name) ?: error("system property $name is not set; run tests through the root Gradle build")

    private fun listProperty(name: String): List<String> = requiredProperty(name).split(",").map { it.trim() }.filter { it.isNotEmpty() }

    class Build(
        private val dir: File,
        private val packagePath: String,
        private val includePackages: List<String> = listOf("com.example.target"),
        private val servesResources: Boolean = true,
        private val library: List<File> = emptyList(),
    ) {
        fun withoutResources(): Build = Build(dir, packagePath, includePackages, servesResources = false, library = library)

        fun withScalaLibrary(version: String): Build = Build(dir, packagePath, includePackages, servesResources, scalaLibrary(version))

        val directory: File get() = dir

        fun classNames(): List<String> {
            val root = File(dir, packagePath)
            return root
                .walkTopDown()
                .filter { it.isFile && it.extension == "class" }
                .map { it.relativeTo(root).path.removeSuffix(".class") }
                .sorted()
                .toList()
        }

        fun classBytes(simpleName: String): ByteArray = File(dir, "$packagePath/$simpleName.class").readBytes()

        val lookup: (String) -> ByteArray? =
            { internalName ->
                File(dir, "$internalName.class").takeIf { it.isFile }?.readBytes()
                    ?: libraryLoader?.getResourceAsStream("$internalName.class")?.use { it.readBytes() }
            }

        private val libraryLoader: java.net.URLClassLoader? by lazy {
            if (library.isEmpty()) null else java.net.URLClassLoader(library.map { it.toURI().toURL() }.toTypedArray(), null)
        }

        val resources: (String) -> ByteArray? =
            { path -> File(dir, path).takeIf { servesResources && it.isFile }?.inputStream()?.use { it.readNBytes(512) } }

        fun analyze(simpleName: String): BranchSiteAnalyzer.Analysis =
            BranchSiteAnalyzer.analyze(
                classBytes(simpleName),
                lookup,
                includePackages = includePackages,
                resourceLookup = resources,
            ) { _, _ -> true }

        fun unreadEntries(simpleName: String): List<Triple<String, String, UnreadShape>> {
            val analysis = analyze(simpleName)
            val entries = mutableListOf<Triple<String, String, UnreadShape>>()
            ClassReader(classBytes(simpleName)).accept(
                object : ClassVisitor(Opcodes.ASM9) {
                    override fun visitMethod(
                        access: Int,
                        name: String,
                        descriptor: String,
                        signature: String?,
                        exceptions: Array<out String>?,
                    ) = null.also {
                        analysis.unreadShape(name, descriptor).takeIf { family -> family != UnreadShape.NONE }?.let {
                            entries +=
                                Triple(name, descriptor, it)
                        }
                    }
                },
                ClassReader.SKIP_CODE,
            )
            return entries
        }

        fun analyzeAsMethodTier(simpleName: String): BranchSiteAnalyzer.Analysis {
            val bytes = classBytes(simpleName)
            val unprobed = mutableSetOf<Pair<String, String>>()
            val excluded = Opcodes.ACC_BRIDGE or Opcodes.ACC_SYNTHETIC or Opcodes.ACC_ABSTRACT or Opcodes.ACC_NATIVE
            ClassReader(bytes).accept(
                object : ClassVisitor(Opcodes.ASM9) {
                    override fun visitMethod(
                        access: Int,
                        name: String,
                        descriptor: String,
                        signature: String?,
                        exceptions: Array<out String>?,
                    ) = null.also { if (access and excluded != 0 || name == "<clinit>") unprobed += name to descriptor }
                },
                ClassReader.SKIP_CODE,
            )
            return BranchSiteAnalyzer.analyze(bytes, lookup, includePackages = includePackages) { name, descriptor ->
                (name to descriptor) !in unprobed
            }
        }

        fun analyzeBranches(simpleName: String): BranchSiteAnalyzer.Analysis =
            BranchSiteAnalyzer.analyze(
                classBytes(simpleName),
                lookup,
                includePackages = includePackages,
            ) { name, _ -> name != "<init>" }

        fun marks(simpleName: String): Marks = Marks(markEntries(simpleName))

        fun markEntries(simpleName: String): List<Triple<String, String, GeneratedBy>> {
            val analysis = analyze(simpleName)
            val entries = mutableListOf<Triple<String, String, GeneratedBy>>()
            ClassReader(classBytes(simpleName)).accept(
                object : ClassVisitor(Opcodes.ASM9) {
                    override fun visitMethod(
                        access: Int,
                        name: String,
                        descriptor: String,
                        signature: String?,
                        exceptions: Array<out String>?,
                    ) = null.also { entries += Triple(name, descriptor, analysis.generatedBy(name, descriptor)) }
                },
                ClassReader.SKIP_CODE,
            )
            return entries
        }
    }

    class Marks(
        private val entries: List<Triple<String, String, GeneratedBy>>,
    ) {
        fun only(name: String): GeneratedBy = entries.filter { it.first == name }.map { it.third }.single()

        fun of(
            name: String,
            descriptor: String,
        ): GeneratedBy = entries.single { it.first == name && it.second == descriptor }.third

        fun all(name: String): List<GeneratedBy> = entries.filter { it.first == name }.map { it.third }

        override fun toString() = entries.joinToString { "${it.first}${it.second}=${it.third}" }
    }
}
