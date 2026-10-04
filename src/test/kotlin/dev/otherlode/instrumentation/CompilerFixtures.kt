package dev.otherlode.instrumentation

import dev.otherlode.export.GeneratedBy
import dev.otherlode.export.UnreadShape
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

    /** The scalac releases in the matrix, oldest first, as `gradle.properties` lists them. */
    val scalacVersions: List<String> get() = listProperty("otherlode.fixtures.scalac.versions")

    /**
     * Whether kotlinc release [version] defaults `-jvm-default` to `enable`, which it does from 2.2;
     * the earlier releases default to `disable`. Versions compare as numbers, so 2.10 is after 2.2.
     */
    fun kotlincDefaultsToJvmDefaultEnable(version: String): Boolean {
        val (major, minor) = version.split(".").map { it.takeWhile(Char::isDigit).toInt() }
        return major > 2 || (major == 2 && minor >= 2)
    }

    private const val KOTLIN_PACKAGE = "com/example/target/kotlinc"
    private const val JAVA_PACKAGE = "com/example/target/javac"
    private const val SCALA_PACKAGE = "com/example/scalatarget"

    /** The build of kotlinc release [version]. */
    fun kotlinc(version: String): Build = Build(File(requiredProperty("otherlode.fixtures.kotlinc.$version.dir")), KOTLIN_PACKAGE)

    /** The build of javac [version]. */
    fun javac(version: String): Build = Build(File(requiredProperty("otherlode.fixtures.javac.$version.dir")), JAVA_PACKAGE)

    /**
     * The build of scalac release [version], which compiles `fixtures-scala3`'s sources for a 3.x
     * release and `fixtures-scala2`'s for a 2.x one.
     */
    fun scalac(version: String): Build =
        Build(File(requiredProperty("otherlode.fixtures.scalac.$version.dir")), SCALA_PACKAGE, listOf("com.example.scalatarget"))

    /** The baseline build of the Scala line [version] belongs to: 2.13.15 for a 2.x release, 3.3.4 for a 3.x one. */
    fun scalaBaseline(version: String): Build =
        Build(
            File(requiredProperty("otherlode.fixtures.${if (version.startsWith("3.")) "scala3" else "scala2"}.dir")),
            SCALA_PACKAGE,
            listOf("com.example.scalatarget"),
        )

    /** What scalac release [version] prints for `-version`, as the build recorded it. */
    fun scalacVersionOutput(version: String): String = File(requiredProperty("otherlode.fixtures.scalac.$version.versionFile")).readText()

    private fun requiredProperty(name: String): String =
        System.getProperty(name) ?: error("system property $name is not set; run tests through the root Gradle build")

    private fun listProperty(name: String): List<String> = requiredProperty(name).split(",").map { it.trim() }.filter { it.isNotEmpty() }

    /** One compiler's class directory. */
    class Build(
        private val dir: File,
        private val packagePath: String,
        private val includePackages: List<String> = listOf("com.example.target"),
        private val servesResources: Boolean = true,
    ) {
        /** This build as a loader that serves its classes but no other resource, so no `.tasty` is found. */
        fun withoutResources(): Build = Build(dir, packagePath, includePackages, servesResources = false)

        /** The root of this build's class files, which a static scan can walk. */
        val directory: File get() = dir

        /** Every class in this build's fixture package, nested ones included, by name as [classBytes] takes it. */
        fun classNames(): List<String> {
            val root = File(dir, packagePath)
            return root
                .walkTopDown()
                .filter { it.isFile && it.extension == "class" }
                .map { it.relativeTo(root).path.removeSuffix(".class") }
                .sorted()
                .toList()
        }

        /** The raw bytes of fixture class [simpleName], which may be a nested name such as `Outer$Inner`. */
        fun classBytes(simpleName: String): ByteArray = File(dir, "$packagePath/$simpleName.class").readBytes()

        /** Reads a class by internal name from this build alone, the way the agent reads one through its loader. */
        val lookup: (String) -> ByteArray? = { internalName -> File(dir, "$internalName.class").takeIf { it.isFile }?.readBytes() }

        /** Reads the leading bytes of a non-class resource of this build, such as a Scala 3 class's `.tasty`, as the agent's loader would. */
        val resources: (String) -> ByteArray? =
            { path -> File(dir, path).takeIf { servesResources && it.isFile }?.inputStream()?.use { it.readNBytes(512) } }

        /**
         * The real analysis of fixture class [simpleName] with every method eligible, so marks
         * and default sites are read for all of them.
         */
        fun analyze(simpleName: String): BranchSiteAnalyzer.Analysis =
            BranchSiteAnalyzer.analyze(
                classBytes(simpleName),
                lookup,
                includePackages = includePackages,
                resourceLookup = resources,
            ) { _, _ -> true }

        /** Every method of fixture class [simpleName] the analysis reports as an unread shape, as (name, descriptor, family). */
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
            return BranchSiteAnalyzer.analyze(bytes, lookup, includePackages = includePackages) { name, descriptor ->
                (name to descriptor) !in unprobed
            }
        }

        /** The analysis as the method tier sees it, constructors left out. */
        fun analyzeBranches(simpleName: String): BranchSiteAnalyzer.Analysis =
            BranchSiteAnalyzer.analyze(
                classBytes(simpleName),
                lookup,
                includePackages = includePackages,
            ) { name, _ -> name != "<init>" }

        /**
         * Every method of fixture class [simpleName] with the mark the analysis gives it, keyed by
         * name then descriptor, so an overloaded name keeps one entry per overload.
         */
        fun marks(simpleName: String): Marks = Marks(markEntries(simpleName))

        /** Every method of fixture class [simpleName] as (name, descriptor, mark), in class-file order. */
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
