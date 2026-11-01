package dev.otherlode.instrumentation.branch

import dev.otherlode.export.GeneratedBy
import dev.otherlode.export.UnreadShape
import dev.otherlode.instrumentation.CompilerFixtures
import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.ClassWriter
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Proves the Scala value-class and trait-forwarder marks (ADR 0080) over the fixtures in
 * `ValueClasses.scala` and `TraitForwarders.scala`, built by the baseline compiler of each Scala
 * line and by every release of the compiler matrix. Each method of a fixture class carries the
 * mark its entry gives it, `NONE` for a method the entry does not name.
 */
class ScalaForwarderMarksTest {
    companion object {
        /** The 3.3.4 baseline, named `baseline`, and every Scala 3 release in the matrix. */
        @JvmStatic
        fun scala3Builds(): List<String> = listOf("baseline") + CompilerFixtures.scalacVersions.filter { it.startsWith("3.") }

        /** The 2.13.15 baseline, named `baseline`, and every Scala 2 release in the matrix. */
        @JvmStatic
        fun scala2Builds(): List<String> = listOf("baseline") + CompilerFixtures.scalacVersions.filter { !it.startsWith("3.") }

        private const val PACKAGE = "com/example/scalatarget"
        private val VALUE = GeneratedBy.VALUE_CLASS
        private val CASE = GeneratedBy.CASE_CLASS
        private val TRAIT = GeneratedBy.TRAIT_FORWARDER

        /** Marks other rules own: scalac's static forwarders and an object's serialization hook. */
        private val OTHER_RULES = setOf(GeneratedBy.STATIC_FORWARDER, GeneratedBy.SCALA_OBJECT)

        /** The members of a case value class's boxed side that scalac writes in both Scala versions. */
        private val CASE_BOXED =
            listOf(
                "canEqual",
                "equals",
                "hashCode",
                "toString",
                "productArity",
                "productPrefix",
                "productElement",
                "productIterator",
                "copy",
                "copy\$default\$1",
            )

        /** The members Scala 2.12 does not write. */
        private val SINCE_2_13 = setOf("productElementName", "productElementNames")
    }

    private fun build(
        key: String,
        scala3: Boolean,
    ): CompilerFixtures.Build =
        if (key == "baseline") {
            CompilerFixtures.scalaBaseline(if (scala3) "3.3.4" else "2.13.15").withScalaLibrary(if (scala3) "3.3.4" else "2.13.15")
        } else {
            CompilerFixtures.scalac(key).withScalaLibrary(key)
        }

    /**
     * Asserts the marks of fixture class [simpleName]. [expected] maps a method name, or a name
     * followed by a descriptor, to its mark; every other method must be `NONE`. A method another
     * rule marks ([OTHER_RULES]) is left out. Each key must name a method, except those in [optional].
     */
    private fun assertMarks(
        key: String,
        scala3: Boolean,
        simpleName: String,
        expected: Map<String, GeneratedBy>,
        optional: Set<String> = emptySet(),
    ) {
        val entries = build(key, scala3).markEntries(simpleName).filter { it.third !in OTHER_RULES }
        val seen = mutableSetOf<String>()
        for ((name, descriptor, mark) in entries) {
            val exact = expected["$name$descriptor"]
            val byName = expected[name]
            val want = exact ?: byName ?: GeneratedBy.NONE
            if (exact != null) seen += "$name$descriptor"
            if (byName != null) seen += name
            assertEquals(want, mark, "$key $simpleName.$name$descriptor")
        }
        val missing = expected.keys - seen - optional
        assertTrue(missing.isEmpty(), "$key $simpleName has no method $missing")
    }

    private fun `an implicit value class and its companion`(
        key: String,
        scala3: Boolean,
    ) {
        assertMarks(key, scala3, "ValueSyntax\$RichInt", mapOf("doubled" to VALUE, "hashCode" to VALUE, "equals" to VALUE))
        assertMarks(key, scala3, "ValueSyntax\$RichInt\$", mapOf("hashCode\$extension" to VALUE, "equals\$extension" to VALUE))
    }

    @ParameterizedTest(name = "scalac {0}")
    @MethodSource("scala3Builds")
    fun `scala 3 - an implicit value class's boxed methods are VALUE_CLASS and its own extension method is not`(key: String) =
        `an implicit value class and its companion`(key, true)

    @ParameterizedTest(name = "scalac {0}")
    @MethodSource("scala2Builds")
    fun `scala 2 - an implicit value class's boxed methods are VALUE_CLASS and its own extension method is not`(key: String) =
        `an implicit value class and its companion`(key, false)

    private fun `plain value classes over every kind of field`(
        key: String,
        scala3: Boolean,
    ) {
        assertMarks(key, scala3, "Weight", mapOf("scaled" to VALUE, "plus" to VALUE, "hashCode" to VALUE, "equals" to VALUE))
        assertMarks(key, scala3, "Weight\$", mapOf("hashCode\$extension" to VALUE, "equals\$extension" to VALUE))
        assertMarks(key, scala3, "Flag", mapOf("flipped" to VALUE, "hashCode" to VALUE, "equals" to VALUE))
        assertMarks(key, scala3, "Flag\$", mapOf("hashCode\$extension" to VALUE, "equals\$extension" to VALUE))
        for (box in listOf("ByteBox", "ShortBox", "CharBox", "FloatBox", "DoubleBox", "TextBox", "GenericBox", "ArrayBox")) {
            assertMarks(key, scala3, box, mapOf("hashCode" to VALUE, "equals" to VALUE))
            assertMarks(key, scala3, "$box\$", mapOf("hashCode\$extension" to VALUE, "equals\$extension" to VALUE))
        }
    }

    @ParameterizedTest(name = "scalac {0}")
    @MethodSource("scala3Builds")
    fun `scala 3 - a plain value class's boxed methods and its hashCode and equals twins are VALUE_CLASS`(key: String) =
        `plain value classes over every kind of field`(key, true)

    @ParameterizedTest(name = "scalac {0}")
    @MethodSource("scala2Builds")
    fun `scala 2 - a plain value class's boxed methods and its hashCode and equals twins are VALUE_CLASS`(key: String) =
        `plain value classes over every kind of field`(key, false)

    private fun `case value classes over a primitive and a String`(
        key: String,
        scala3: Boolean,
    ) {
        val boxed = CASE_BOXED.associateWith { CASE } + SINCE_2_13.associateWith { CASE } + if (scala3) mapOf("_1" to CASE) else emptyMap()
        val companion =
            CASE_COMPANION + SINCE_2_13_COMPANION.associateWith { CASE } +
                if (scala3) mapOf("_1\$extension" to CASE) else mapOf("productIterator\$extension" to CASE)
        assertMarks(key, scala3, "Meters", boxed, SINCE_2_13)
        assertMarks(key, scala3, "Meters\$", companion + ("apply(D)D" to CASE), SINCE_2_13_COMPANION)
        assertMarks(key, scala3, "Label", boxed + ("shout" to VALUE), SINCE_2_13)
        assertMarks(
            key,
            scala3,
            "Label\$",
            companion + ("apply(Ljava/lang/String;)Ljava/lang/String;" to CASE) + ("toString\$extension" to GeneratedBy.NONE),
            SINCE_2_13_COMPANION,
        )
    }

    @ParameterizedTest(name = "scalac {0}")
    @MethodSource("scala3Builds")
    fun `scala 3 - a case value class's plumbing is CASE_CLASS on both sides`(key: String) =
        `case value classes over a primitive and a String`(key, true)

    @ParameterizedTest(name = "scalac {0}")
    @MethodSource("scala2Builds")
    fun `scala 2 - a case value class's plumbing is CASE_CLASS on both sides`(key: String) =
        `case value classes over a primitive and a String`(key, false)

    private fun `a final class with a value class's names and other bodies`(
        key: String,
        scala3: Boolean,
    ) {
        assertMarks(key, scala3, "Almost", emptyMap())
        assertMarks(key, scala3, "Almost\$", emptyMap())
    }

    @ParameterizedTest(name = "scalac {0}")
    @MethodSource("scala3Builds")
    fun `scala 3 - a final class whose methods have a value class's names and other bodies is ordinary code`(key: String) =
        `a final class with a value class's names and other bodies`(key, true)

    @ParameterizedTest(name = "scalac {0}")
    @MethodSource("scala2Builds")
    fun `scala 2 - a final class whose methods have a value class's names and other bodies is ordinary code`(key: String) =
        `a final class with a value class's names and other bodies`(key, false)

    private fun `a default-argument getter of a trait is left to its own rule`(
        key: String,
        scala3: Boolean,
    ) {
        assertMarks(key, scala3, "Tr", mapOf("t\$" to TRAIT))
        assertMarks(key, scala3, "TrImpl", mapOf("t" to TRAIT))
    }

    @ParameterizedTest(name = "scalac {0}")
    @MethodSource("scala3Builds")
    fun `scala 3 - a trait's default-argument getter and its accessor are not forwarders`(key: String) =
        `a default-argument getter of a trait is left to its own rule`(key, true)

    @ParameterizedTest(name = "scalac {0}")
    @MethodSource("scala2Builds")
    fun `scala 2 - a trait's default-argument getter and its accessor are not forwarders`(key: String) =
        `a default-argument getter of a trait is left to its own rule`(key, false)

    private fun `a trait and the classes that mix it in`(
        key: String,
        scala3: Boolean,
    ) {
        val trait = mapOf("greet\$" to TRAIT, "total\$" to TRAIT, "ping\$" to TRAIT)
        assertMarks(key, scala3, "Greets", trait)
        assertMarks(key, scala3, "LoudGreets", mapOf("loud\$" to TRAIT))
        assertMarks(key, scala3, "Greeter", mapOf("greet" to TRAIT, "total" to TRAIT, "ping" to TRAIT))
        assertMarks(key, scala3, "OverridingGreeter", mapOf("total" to TRAIT, "ping" to TRAIT))
        assertMarks(key, scala3, "WrappingGreeter", mapOf("total" to TRAIT, "ping" to TRAIT))
        assertMarks(key, scala3, "DeepGreeter", mapOf("greet" to TRAIT, "total" to TRAIT, "ping" to TRAIT, "loud" to TRAIT))
    }

    @ParameterizedTest(name = "scalac {0}")
    @MethodSource("scala3Builds")
    fun `scala 3 - a trait's static entry and a mixing class's forwarder are TRAIT_FORWARDER and the default method is not`(key: String) =
        `a trait and the classes that mix it in`(key, true)

    @ParameterizedTest(name = "scalac {0}")
    @MethodSource("scala2Builds")
    fun `scala 2 - a trait's static entry and a mixing class's forwarder are TRAIT_FORWARDER and the default method is not`(key: String) =
        `a trait and the classes that mix it in`(key, false)

    private val comparatorDefaults =
        listOf("reversed", "thenComparing", "thenComparingInt", "thenComparingLong", "thenComparingDouble")

    /** Asserts that every method of [simpleName] called one of [names] is a trait forwarder. */
    private fun forwardedDefaults(
        key: String,
        scala3: Boolean,
        simpleName: String,
        names: List<String>,
    ) {
        val entries = build(key, scala3).markEntries(simpleName).filter { it.third !in OTHER_RULES }
        for (name in names) {
            val marks = entries.filter { it.first == name }
            assertTrue(marks.isNotEmpty(), "$key $simpleName has no $name")
            for ((_, descriptor, mark) in marks) assertEquals(TRAIT, mark, "$key $simpleName.$name$descriptor")
        }
    }

    private fun `a class that implements a Java interface with default methods`(
        key: String,
        scala3: Boolean,
    ) {
        forwardedDefaults(key, scala3, "ComparingHandler", comparatorDefaults)
        assertMarks(key, scala3, "ComparingHandler", comparatorDefaults.associateWith { TRAIT }, comparatorDefaults.toSet())
        forwardedDefaults(key, scala3, "EvenLength", listOf("and", "negate", "or"))
        forwardedDefaults(key, scala3, "Shout", listOf("compose", "andThen"))
        assertMarks(
            key,
            scala3,
            "RenamingHandler",
            comparatorDefaults.associateWith {
                TRAIT
            } + ("flipped" to GeneratedBy.NONE),
            comparatorDefaults.toSet(),
        )
        assertMarks(
            key,
            scala3,
            "OverridingHandler",
            comparatorDefaults
                .filter {
                    it != "reversed"
                }.associateWith { TRAIT },
            comparatorDefaults.toSet(),
        )
    }

    @ParameterizedTest(name = "scalac {0}")
    @MethodSource("scala3Builds")
    fun `scala 3 - a forwarder to a Java default method is TRAIT_FORWARDER and a method of the adopter's is not`(key: String) =
        `a class that implements a Java interface with default methods`(key, true)

    @ParameterizedTest(name = "scalac {0}")
    @MethodSource("scala2Builds")
    fun `scala 2 - a forwarder to a Java default method is TRAIT_FORWARDER and a method of the adopter's is not`(key: String) =
        `a class that implements a Java interface with default methods`(key, false)

    @ParameterizedTest(name = "scalac {0}")
    @MethodSource("scala3Builds")
    fun `scala 3 - a forwarder to a Java default method reached through a trait is TRAIT_FORWARDER`(key: String) {
        val entries = build(key, true).markEntries("TransitiveHandler").filter { it.third !in OTHER_RULES }
        val forwarders = entries.filter { it.first in comparatorDefaults }
        assertTrue(forwarders.isNotEmpty(), "$key TransitiveHandler has no forwarder, so scalac left the defaults to the interface")
        for ((name, descriptor, mark) in forwarders) assertEquals(TRAIT, mark, "$key TransitiveHandler.$name$descriptor")
    }

    private fun `a function over a primitive`(
        key: String,
        scala3: Boolean,
    ) {
        fun markOf(
            simpleName: String,
            method: String,
        ) = build(key, scala3).markEntries(simpleName).single { it.first + it.second == method }.third

        assertEquals(TRAIT, markOf("Inc", "apply(I)I"), "$key Inc.apply")
        assertEquals(GeneratedBy.NONE, markOf("Inc", "apply\$mcII\$sp(I)I"), "$key Inc.apply\$mcII\$sp")
        assertEquals(TRAIT, markOf("Halve", "apply(D)D"), "$key Halve.apply")
        assertEquals(GeneratedBy.NONE, markOf("Halve", "apply\$mcDD\$sp(D)D"), "$key Halve.apply\$mcDD\$sp")
        assertEquals(TRAIT, markOf("Inc2", "apply(I)I"), "$key Inc2.apply")
        assertEquals(GeneratedBy.NONE, markOf("Inc2", "apply\$mcII\$sp(I)I"), "$key Inc2.apply\$mcII\$sp")
        assertMarks(key, scala3, "Look", emptyMap())
        assertEquals(emptyList(), build(key, scala3).unreadEntries("Look"), "$key Look")
    }

    @ParameterizedTest(name = "scalac {0}")
    @MethodSource("scala3Builds")
    fun `scala 3 - a primitive function's apply that forwards to its specialised twin is TRAIT_FORWARDER`(key: String) =
        `a function over a primitive`(key, true)

    @ParameterizedTest(name = "scalac {0}")
    @MethodSource("scala2Builds")
    fun `scala 2 - a primitive function's apply that forwards to its specialised twin is TRAIT_FORWARDER`(key: String) =
        `a function over a primitive`(key, false)

    @Test
    fun `scala 2 - a specialised apply whose twin's supertype cannot be read is an unread shape`() {
        val unreadable: (String) -> ByteArray? = { if (it.startsWith("scala/")) null else scala2Fixtures.lookup(it) }
        val analysis = analyse(scala2Fixtures.classBytes("Inc"), unreadable)

        assertEquals(GeneratedBy.NONE, analysis.generatedBy("apply", "(I)I"))
        assertEquals(UnreadShape.TRAIT_FORWARDER, analysis.unreadShape("apply", "(I)I"))
    }

    @Test
    fun `a specialised apply with one more instruction is ordinary code`() {
        val analysis = analyse(altered(fixtures.classBytes("Inc"), "apply"), fixtures.lookup)

        assertEquals(GeneratedBy.NONE, analysis.generatedBy("apply", "(I)I"))
    }

    @Test
    fun `a forwarder to a Java default method with one more instruction is ordinary code and its siblings stay marked`() {
        val analysis = analyse(altered(fixtures.classBytes("ComparingHandler"), "reversed"), fixtures.lookup)

        assertEquals(GeneratedBy.NONE, analysis.generatedBy("reversed", "()Ljava/util/Comparator;"))
        assertEquals(TRAIT, analysis.generatedBy("thenComparingInt", "(Ljava/util/function/ToIntFunction;)Ljava/util/Comparator;"))
    }

    private val withJdk: (String) -> ByteArray? = { name ->
        fixtures.lookup(name) ?: ClassLoader.getSystemResourceAsStream("$name.class")?.use { it.readBytes() }
    }

    @Test
    fun `a forwarder to a Java default method is marked when the interface can be read and declares the default`() {
        val analysis = analyse(fixtures.classBytes("ComparingHandler"), withJdk)

        assertEquals(TRAIT, analysis.generatedBy("reversed", "()Ljava/util/Comparator;"))
        assertEquals(TRAIT, analysis.generatedBy("thenComparingInt", "(Ljava/util/function/ToIntFunction;)Ljava/util/Comparator;"))
    }

    @Test
    fun `a forwarder to a Java method the readable interface does not declare as a default is ordinary code`() {
        val bare = ClassWriter(0)
        bare.visit(
            Opcodes.V17,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT,
            "java/util/Comparator",
            null,
            "java/lang/Object",
            null,
        )
        bare.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, "reversed", "()Ljava/util/Comparator;", null, null).visitEnd()
        bare.visitEnd()
        val lookup: (String) -> ByteArray? = { if (it == "java/util/Comparator") bare.toByteArray() else fixtures.lookup(it) }
        val analysis = analyse(fixtures.classBytes("ComparingHandler"), lookup)

        assertEquals(GeneratedBy.NONE, analysis.generatedBy("reversed", "()Ljava/util/Comparator;"))
        assertEquals(
            GeneratedBy.NONE,
            analysis.generatedBy("thenComparingInt", "(Ljava/util/function/ToIntFunction;)Ljava/util/Comparator;"),
        )
    }

    @Test
    fun `analysing a class that mixes in many traits reads each class once`() {
        val loader =
            java.net.URLClassLoader(
                System
                    .getProperty("otherlode.fixtures.scala2.classpath")
                    .split(java.io.File.pathSeparator)
                    .map {
                        java.io
                            .File(it)
                            .toURI()
                            .toURL()
                    }.toTypedArray(),
                null,
            )
        loader.use {
            val reads = mutableMapOf<String, Int>()
            val counting: (String) -> ByteArray? = { name ->
                reads.merge(name, 1, Int::plus)
                scala2Fixtures.lookup(name) ?: loader.getResourceAsStream("$name.class")?.use { it.readBytes() }
            }
            ScalaGeneratedMethods.analyse(scala2Fixtures.classBytes("MySeq"), counting) { null }

            val repeated = reads.filterValues { it > 1 }
            assertTrue(repeated.isEmpty(), "classes read more than once: $repeated")
            assertTrue(reads.size > 5, "too few classes read to prove anything: ${reads.keys}")
        }
    }

    /** [classBytes] with a `nop` first in the method [method], and with the `final` flag of the class cleared when [open]. */
    private fun altered(
        classBytes: ByteArray,
        method: String,
        open: Boolean = false,
    ): ByteArray {
        val writer = ClassWriter(0)
        ClassReader(classBytes).accept(
            object : ClassVisitor(Opcodes.ASM9, writer) {
                override fun visit(
                    version: Int,
                    access: Int,
                    name: String,
                    signature: String?,
                    superName: String?,
                    interfaces: Array<out String>?,
                ) = super.visit(version, if (open) access and Opcodes.ACC_FINAL.inv() else access, name, signature, superName, interfaces)

                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor {
                    val delegate = super.visitMethod(access, name, descriptor, signature, exceptions)
                    if (name != method) return delegate
                    return object : MethodVisitor(Opcodes.ASM9, delegate) {
                        override fun visitCode() {
                            super.visitCode()
                            super.visitInsn(Opcodes.NOP)
                        }
                    }
                }
            },
            0,
        )
        return writer.toByteArray()
    }

    private fun analyse(
        classBytes: ByteArray,
        lookup: (String) -> ByteArray?,
    ): BranchSiteAnalyzer.Analysis = BranchSiteAnalyzer.analyze(classBytes, lookup, listOf("com.example.scalatarget")) { _, _ -> true }

    private val fixtures = CompilerFixtures.scalaBaseline("3.3.4")
    private val scala2Fixtures = CompilerFixtures.scalaBaseline("2.13.15")

    private class Shape(
        val simpleName: String,
        val method: String,
        val descriptor: String,
        val tastyOwner: String,
        val family: UnreadShape,
    )

    /** One forwarder of each outline, which the tests below alter. */
    private val shapes =
        listOf(
            Shape("Weight", "scaled", "(DJ)D", "Weight", UnreadShape.VALUE_CLASS),
            Shape("Weight\$", "hashCode\$extension", "(J)I", "Weight", UnreadShape.VALUE_CLASS),
            Shape("Weight\$", "equals\$extension", "(JLjava/lang/Object;)Z", "Weight", UnreadShape.VALUE_CLASS),
            Shape("Greeter", "greet", "(Ljava/lang/String;)Ljava/lang/String;", "Greeter", UnreadShape.TRAIT_FORWARDER),
            Shape("Greets", "ping\$", "(L$PACKAGE/Greets;)V", "Greets", UnreadShape.TRAIT_FORWARDER),
            Shape("ComparingHandler", "reversed", "()Ljava/util/Comparator;", "ComparingHandler", UnreadShape.TRAIT_FORWARDER),
            Shape("Inc", "apply", "(I)I", "Inc", UnreadShape.TRAIT_FORWARDER),
        )

    private fun tasty(
        owner: String,
        tooling: String?,
    ): (String) -> ByteArray? =
        { path ->
            if (tooling != null &&
                path == "$PACKAGE/$owner.tasty"
            ) {
                BrokenScalaFixtures.tastyHeader(tooling)
            } else {
                null
            }
        }

    private fun analyseWith(
        classBytes: ByteArray,
        lookup: (String) -> ByteArray?,
        resources: (String) -> ByteArray?,
    ): BranchSiteAnalyzer.Analysis =
        BranchSiteAnalyzer.analyze(classBytes, lookup, listOf("com.example.scalatarget"), resourceLookup = resources) { name, _ ->
            name !=
                "<clinit>"
        }

    @Test
    fun `scala 2 - a forwarder with a body that is not scalac's is an unread shape of its family`() {
        for (shape in shapes) {
            val bytes = altered(scala2Fixtures.classBytes(shape.simpleName), shape.method)
            val analysis = analyseWith(bytes, scala2Fixtures.lookup) { null }

            assertEquals(GeneratedBy.NONE, analysis.generatedBy(shape.method, shape.descriptor), "${shape.simpleName}.${shape.method}")
            assertEquals(shape.family, analysis.unreadShape(shape.method, shape.descriptor), "${shape.simpleName}.${shape.method}")
        }
    }

    @Test
    fun `scala 3 - a forwarder with a body that is not scalac's is ordinary code on a read release`() {
        for (shape in shapes) {
            val bytes = altered(fixtures.classBytes(shape.simpleName), shape.method)
            val analysis = analyseWith(bytes, fixtures.lookup, tasty(shape.tastyOwner, "Scala 3.3.4"))

            assertEquals(UnreadShape.NONE, analysis.unreadShape(shape.method, shape.descriptor), "${shape.simpleName}.${shape.method}")
            assertEquals(GeneratedBy.NONE, analysis.generatedBy(shape.method, shape.descriptor), "${shape.simpleName}.${shape.method}")
            assertNull(analysis.unreadRelease)
        }
    }

    @Test
    fun `scala 3 - a forwarder with a body that is not scalac's is an unread shape of its family on an unread release`() {
        for (shape in shapes) {
            val bytes = altered(fixtures.classBytes(shape.simpleName), shape.method)
            val analysis = analyseWith(bytes, fixtures.lookup, tasty(shape.tastyOwner, "Scala 3.99.0"))

            assertEquals(shape.family, analysis.unreadShape(shape.method, shape.descriptor), "${shape.simpleName}.${shape.method}")
            assertEquals("3.99.0", analysis.unreadRelease)
        }
    }

    @Test
    fun `scala 3 - a forwarder with a body that is not scalac's is an unread shape of its family when no tasty file is read`() {
        for (shape in shapes) {
            val bytes = altered(fixtures.classBytes(shape.simpleName), shape.method)
            val analysis = analyseWith(bytes, fixtures.lookup) { null }

            assertEquals(shape.family, analysis.unreadShape(shape.method, shape.descriptor), "${shape.simpleName}.${shape.method}")
        }
    }

    @Test
    fun `scala 2 - an adopter's override of a trait method with its own body is ordinary code and its sibling forwarders stay marked`() {
        val analysis = analyseWith(scala2Fixtures.classBytes("OverridingGreeter"), scala2Fixtures.lookup) { null }

        assertEquals(UnreadShape.NONE, analysis.unreadShape("greet", "(Ljava/lang/String;)Ljava/lang/String;"))
        assertEquals(GeneratedBy.NONE, analysis.generatedBy("greet", "(Ljava/lang/String;)Ljava/lang/String;"))
        assertEquals(TRAIT, analysis.generatedBy("total", "(IJD)J"))
    }

    @Test
    fun `scala 2 - an override that calls super and does more is an unread shape`() {
        val analysis = analyseWith(scala2Fixtures.classBytes("WrappingGreeter"), scala2Fixtures.lookup) { null }

        assertEquals(UnreadShape.TRAIT_FORWARDER, analysis.unreadShape("greet", "(Ljava/lang/String;)Ljava/lang/String;"))
        assertEquals(TRAIT, analysis.generatedBy("ping", "()V"))
    }

    private val reversed = "reversed" to "()Ljava/util/Comparator;"

    @Test
    fun `scala 3 - a Java default forwarder is marked on an unread release and the decoys hold nothing`() {
        for (release in listOf("Scala 3.99.0", "Scala 3.3.4")) {
            val unread = release == "Scala 3.99.0"
            val resources = tasty("ComparingHandler", release)
            val marked = analyseWith(fixtures.classBytes("ComparingHandler"), fixtures.lookup, resources)
            assertEquals(TRAIT, marked.generatedBy(reversed.first, reversed.second), release)
            assertEquals(UnreadShape.NONE, marked.unreadShape(reversed.first, reversed.second), release)

            val renaming = analyseWith(fixtures.classBytes("RenamingHandler"), fixtures.lookup, tasty("RenamingHandler", release))
            assertEquals(GeneratedBy.NONE, renaming.generatedBy("flipped", "()Ljava/util/Comparator;"), release)
            assertEquals(UnreadShape.NONE, renaming.unreadShape("flipped", "()Ljava/util/Comparator;"), release)

            val overriding = analyseWith(fixtures.classBytes("OverridingHandler"), fixtures.lookup, tasty("OverridingHandler", release))
            assertEquals(GeneratedBy.NONE, overriding.generatedBy(reversed.first, reversed.second), release)
            assertEquals(UnreadShape.NONE, overriding.unreadShape(reversed.first, reversed.second), release)

            val wrapping = analyseWith(fixtures.classBytes("WrappingHandler"), fixtures.lookup, tasty("WrappingHandler", release))
            assertEquals(GeneratedBy.NONE, wrapping.generatedBy(reversed.first, reversed.second), release)
            assertEquals(
                if (unread) UnreadShape.TRAIT_FORWARDER else UnreadShape.NONE,
                wrapping.unreadShape(reversed.first, reversed.second),
                release,
            )
        }
    }

    @Test
    fun `scala 2 - an override of a Java default with work around super is an unread shape and a renamed call is not`() {
        val wrapping = analyseWith(scala2Fixtures.classBytes("WrappingHandler"), scala2Fixtures.lookup) { null }
        assertEquals(UnreadShape.TRAIT_FORWARDER, wrapping.unreadShape(reversed.first, reversed.second))
        assertEquals(TRAIT, wrapping.generatedBy("thenComparingInt", "(Ljava/util/function/ToIntFunction;)Ljava/util/Comparator;"))

        val renaming = analyseWith(scala2Fixtures.classBytes("RenamingHandler"), scala2Fixtures.lookup) { null }
        assertEquals(UnreadShape.NONE, renaming.unreadShape("flipped", "()Ljava/util/Comparator;"))
        assertEquals(GeneratedBy.NONE, renaming.generatedBy("flipped", "()Ljava/util/Comparator;"))
    }

    @Test
    fun `a trait forwarder with one more instruction is ordinary code and its siblings stay marked`() {
        val analysis = analyse(altered(fixtures.classBytes("Greeter"), "greet"), fixtures.lookup)

        assertEquals(GeneratedBy.NONE, analysis.generatedBy("greet", "(Ljava/lang/String;)Ljava/lang/String;"))
        assertEquals(TRAIT, analysis.generatedBy("ping", "()V"))
    }

    @Test
    fun `a trait forwarder is marked only when its trait can be read`() {
        val analysis = analyse(fixtures.classBytes("Greeter")) { if (it.endsWith("/Greets")) null else fixtures.lookup(it) }

        assertEquals(GeneratedBy.NONE, analysis.generatedBy("greet", "(Ljava/lang/String;)Ljava/lang/String;"))
    }

    @Test
    fun `a trait's static entry with one more instruction is ordinary code`() {
        val analysis = analyse(altered(fixtures.classBytes("Greets"), "ping\$"), fixtures.lookup)

        assertEquals(GeneratedBy.NONE, analysis.generatedBy("ping\$", "(L$PACKAGE/Greets;)V"))
        assertEquals(TRAIT, analysis.generatedBy("greet\$", "(L$PACKAGE/Greets;Ljava/lang/String;)Ljava/lang/String;"))
    }

    @Test
    fun `a value class's boxed method with one more instruction is ordinary code and its siblings stay marked`() {
        val analysis = analyse(altered(fixtures.classBytes("ValueSyntax\$RichInt"), "doubled"), fixtures.lookup)

        assertEquals(GeneratedBy.NONE, analysis.generatedBy("doubled", "()I"))
        assertEquals(VALUE, analysis.generatedBy("hashCode", "()I"))
    }

    @Test
    fun `a class that is not final is not a value class`() {
        val analysis = analyse(altered(fixtures.classBytes("ValueSyntax\$RichInt"), "", open = true), fixtures.lookup)

        assertEquals(GeneratedBy.NONE, analysis.generatedBy("doubled", "()I"))
    }

    @Test
    fun `a value class whose companion cannot be read marks no boxed method`() {
        val analysis = analyse(fixtures.classBytes("Weight")) { if (it.endsWith("Weight$")) null else fixtures.lookup(it) }

        assertEquals(GeneratedBy.NONE, analysis.generatedBy("scaled", "(DJ)D"))
    }

    @Test
    fun `a companion whose value class cannot be read marks no extension method`() {
        val analysis = analyse(fixtures.classBytes("Weight\$")) { if (it.endsWith("/Weight")) null else fixtures.lookup(it) }

        assertEquals(GeneratedBy.NONE, analysis.generatedBy("hashCode\$extension", "(J)I"))
    }

    @Test
    fun `an extension method with one more instruction is ordinary code`() {
        val analysis = analyse(altered(fixtures.classBytes("Label\$"), "canEqual\$extension"), fixtures.lookup)

        assertEquals(GeneratedBy.NONE, analysis.generatedBy("canEqual\$extension", "(Ljava/lang/String;Ljava/lang/Object;)Z"))
        assertEquals(CASE, analysis.generatedBy("productArity\$extension", "(Ljava/lang/String;)I"))
    }
}

private val CASE_COMPANION =
    listOf(
        "hashCode\$extension",
        "equals\$extension",
        "toString\$extension",
        "canEqual\$extension",
        "productArity\$extension",
        "productPrefix\$extension",
        "productElement\$extension",
        "copy\$extension",
        "copy\$default\$1\$extension",
        "unapply",
        "toString",
    ).associateWith { GeneratedBy.CASE_CLASS }

private val SINCE_2_13_COMPANION = setOf("productElementName\$extension")
