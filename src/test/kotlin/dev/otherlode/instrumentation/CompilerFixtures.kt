package dev.otherlode.instrumentation

import dev.otherlode.export.GeneratedBy
import dev.otherlode.instrumentation.branch.BranchSiteAnalyzer
import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.Opcodes
import java.io.File

/**
 * The compiler matrix: one fixture build per kotlinc release and per javac version, each the same
 * source set compiled by that compiler (`fixtures-compilers`). The root build passes each build's
 * class directory in as a system property and never as a test dependency, so JUnit discovery
 * never loads the fixtures.
 */
object CompilerFixtures {
    /** The kotlinc releases in the matrix, oldest first, as `gradle.properties` lists them. */
    val kotlincVersions: List<String> get() = listProperty("otherlode.fixtures.kotlinc.versions")

    /** The javac versions in the matrix, oldest first, as `gradle.properties` lists them. */
    val javacVersions: List<String> get() = listProperty("otherlode.fixtures.javac.versions")

    /** The shared Kotlin fixture source holding the `// marker:` comments the coroutine tests read lines from. */
    val kotlincSuspendsSource: File get() = File(requiredProperty("otherlode.fixtures.kotlinc.suspendsSource"))

    /** The javac versions that also compile the pattern-switch sources. */
    val javacPatternVersions: List<String> get() = javacVersions.filter { it.toInt() >= 21 }

    /** The kotlinc releases whose default `-jvm-default` mode is `enable`; the earlier ones default to `disable`. */
    val kotlincJvmDefaultEnableVersions = setOf("2.2.21", "2.4.20")

    private const val KOTLIN_PACKAGE = "com/example/target/kotlinc"
    private const val JAVA_PACKAGE = "com/example/target/javac"

    /** The build of kotlinc release [version]. */
    fun kotlinc(version: String): Build = Build(File(requiredProperty("otherlode.fixtures.kotlinc.$version.dir")), KOTLIN_PACKAGE)

    /** The build of javac [version]. */
    fun javac(version: String): Build = Build(File(requiredProperty("otherlode.fixtures.javac.$version.dir")), JAVA_PACKAGE)

    private fun requiredProperty(name: String): String =
        System.getProperty(name) ?: error("system property $name is not set; run tests through the root Gradle build")

    private fun listProperty(name: String): List<String> = requiredProperty(name).split(",").map { it.trim() }.filter { it.isNotEmpty() }

    /** One compiler's class directory. */
    class Build(
        private val dir: File,
        private val packagePath: String,
    ) {
        /** The root of this build's class files, which a static scan can walk. */
        val directory: File get() = dir

        /** The raw bytes of fixture class [simpleName], which may be a nested name such as `Outer$Inner`. */
        fun classBytes(simpleName: String): ByteArray = File(dir, "$packagePath/$simpleName.class").readBytes()

        /** Reads a class by internal name from this build alone, the way the agent reads one through its loader. */
        val lookup: (String) -> ByteArray? = { internalName -> File(dir, "$internalName.class").takeIf { it.isFile }?.readBytes() }

        /**
         * The real analysis of fixture class [simpleName] with every method eligible, so marks
         * and default sites are read for all of them.
         */
        fun analyze(simpleName: String): BranchSiteAnalyzer.Analysis =
            BranchSiteAnalyzer.analyze(
                classBytes(simpleName),
                lookup,
                includePackages = listOf("com.example.target"),
            ) { _, _ -> true }

        /**
         * The analysis of fixture class [simpleName] with the methods the method tier probes: no
         * bridge, synthetic, abstract or native method and no `<clinit>`, so a call to a bridge in
         * the class is passed through as it is in the agent.
         */
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
            return BranchSiteAnalyzer.analyze(bytes, lookup, includePackages = listOf("com.example.target")) { name, descriptor ->
                (name to descriptor) !in unprobed
            }
        }

        /** The analysis as the method tier sees it, constructors left out. */
        fun analyzeBranches(simpleName: String): BranchSiteAnalyzer.Analysis =
            BranchSiteAnalyzer.analyze(
                classBytes(simpleName),
                lookup,
                includePackages = listOf("com.example.target"),
            ) { name, _ -> name != "<init>" }

        /**
         * Every method of fixture class [simpleName] with the mark the analysis gives it, keyed by
         * name then descriptor, so an overloaded name keeps one entry per overload.
         */
        fun marks(simpleName: String): Marks {
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
            return Marks(entries)
        }
    }

    /** A class's methods with their marks. */
    class Marks(
        private val entries: List<Triple<String, String, GeneratedBy>>,
    ) {
        /** The mark of the one method called [name]; fails when the class has none or several. */
        fun only(name: String): GeneratedBy = entries.filter { it.first == name }.map { it.third }.single()

        /** The mark of [name] with exactly [descriptor]. */
        fun of(
            name: String,
            descriptor: String,
        ): GeneratedBy = entries.single { it.first == name && it.second == descriptor }.third

        /** The marks of every method called [name], in class-file order. */
        fun all(name: String): List<GeneratedBy> = entries.filter { it.first == name }.map { it.third }

        override fun toString() = entries.joinToString { "${it.first}${it.second}=${it.third}" }
    }
}
