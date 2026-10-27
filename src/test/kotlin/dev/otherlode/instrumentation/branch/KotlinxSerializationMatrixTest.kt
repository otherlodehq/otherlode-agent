package dev.otherlode.instrumentation.branch

import dev.otherlode.export.GeneratedBy
import dev.otherlode.export.UnreadShape
import dev.otherlode.instrumentation.CompilerFixtures
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs the kotlinx.serialization marks (ADR 0078) over the same sources compiled by each kotlinc
 * release with its own compiler plugin, and by each release that still has the K1 frontend under
 * `-language-version 1.9`. Under K2 the `$serializer` class is synthetic, so only the K1 builds
 * leave it to probe, but its members read the same.
 */
class KotlinxSerializationMatrixTest {
    companion object {
        @JvmStatic
        fun keys(): List<String> = CompilerFixtures.kotlincSerializationKeys

        @JvmStatic
        fun k1Keys(): List<String> = keys().filter(::isK1)

        @JvmStatic
        fun k1ModeKeys(): List<String> = keys().filter { it.endsWith("-k1") }

        @JvmStatic
        fun k2ReleaseKeys(): List<String> = keys().filter { !it.startsWith("1.") }

        private fun isK1(key: String) = key.startsWith("1.") || key.endsWith("-k1")

        private const val SERIALIZER = "Lkotlinx/serialization/KSerializer;"
        private const val ENCODER = "Lkotlinx/serialization/encoding/Encoder;"
        private const val DECODER = "Lkotlinx/serialization/encoding/Decoder;"
        private const val SELF = "com/example/target/kotlinc/"
        private val MARKED = GeneratedBy.KOTLINX_SERIALIZATION
        private val LAMBDA_NAME = Regex(".*\\$\\d+$")
    }

    private fun entries(
        key: String,
        simpleName: String,
    ) = CompilerFixtures.kotlincSerialization(key).markEntries(simpleName)

    private fun serializerDescriptor(count: Int) = "(${SERIALIZER.repeat(count)})$SERIALIZER"

    @ParameterizedTest(name = "kotlinc {0}")
    @MethodSource("keys")
    fun `every member of a serializer class except its type initializer is KOTLINX_SERIALIZATION`(key: String) {
        listOf(
            "SerPlain",
            "SerData",
            "SerDefaults",
            "SerHolder",
            "SerNamed",
            "SerFlag",
            "SerSealedImpl",
            "SerShape\$Circle",
            "SerShape\$Square",
            "SerBox",
            "SerPair",
            "SerValue",
            "SerMetaUser",
        ).forEach { name ->
            val members = entries(key, "$name\$\$serializer")
            val names = members.map { it.first }

            listOf("serialize", "deserialize", "childSerializers", "getDescriptor").forEach {
                assertTrue(names.any { member -> member == it || member.startsWith("$it-") }, "$name has $it: $names")
            }
            assertTrue(("<init>" to "()V") in members.map { it.first to it.second }, "$name has its private constructor")
            members.forEach { (member, descriptor, mark) ->
                val expected = if (member == "<clinit>") GeneratedBy.NONE else MARKED
                assertEquals(expected, mark, "$name\$\$serializer.$member$descriptor")
            }
        }
    }

    @ParameterizedTest(name = "kotlinc {0}")
    @MethodSource("keys")
    fun `a generic serializer's constructor, type serializers and getters are KOTLINX_SERIALIZATION`(key: String) {
        val box = entries(key, "SerBox\$\$serializer")
        val pair = entries(key, "SerPair\$\$serializer")

        assertEquals(MARKED, box.single { it.first == "<init>" && it.second == "($SERIALIZER)V" }.third)
        assertEquals(MARKED, box.single { it.first == "getTypeSerial0" }.third)
        assertEquals(MARKED, pair.single { it.first == "<init>" && it.second == "($SERIALIZER$SERIALIZER)V" }.third)
        assertEquals(MARKED, pair.single { it.first == "getTypeSerial0" }.third)
        assertEquals(MARKED, pair.single { it.first == "getTypeSerial1" }.third)
    }

    @ParameterizedTest(name = "kotlinc {0}")
    @MethodSource("keys")
    fun `a value class's mangled serialize and deserialize are KOTLINX_SERIALIZATION`(key: String) {
        val members = entries(key, "SerValue\$\$serializer")

        assertTrue(members.any { it.first.startsWith("serialize-") && it.third == MARKED }, "$members")
        assertTrue(members.any { it.first.startsWith("deserialize-") && it.third == MARKED }, "$members")
    }

    @ParameterizedTest(name = "kotlinc {0}")
    @MethodSource("keys")
    fun `serializer() of a serializable class's companion or object is KOTLINX_SERIALIZATION whatever its body`(key: String) {
        mapOf(
            "SerPlain\$Companion" to 0,
            "SerData\$Companion" to 0,
            "SerDefaults\$Companion" to 0,
            "SerHolder\$Companion" to 0,
            "SerValue\$Companion" to 0,
            "SerSealedImpl\$Companion" to 0,
            "SerShape\$Circle\$Companion" to 0,
            "SerFlag\$Companion" to 0,
            "SerNamed\$Factory" to 0,
            "SerWithObject\$Companion" to 0,
            "SerWithClass\$Companion" to 0,
            "SerShape\$Companion" to 0,
            "SerAbstract\$Companion" to 0,
            "SerColor\$Companion" to 0,
            "SerSingleton" to 0,
            "SerBox\$Companion" to 1,
            "SerPair\$Companion" to 2,
            "SerSealedInterface\$Companion" to 0,
            "SerSealedGeneric\$Companion" to 1,
            "SerAbstractGeneric\$Companion" to 1,
            "SerCustomBox\$Companion" to 1,
            "SerMetaUser\$Companion" to 0,
            "SerHost\$Companion" to 0,
            "SerPrivateCompanion\$Companion" to 0,
            "SerGenericHost\$Companion" to 0,
        ).forEach { (name, typeParameters) ->
            assertEquals(
                MARKED,
                CompilerFixtures.kotlincSerialization(key).marks(name).of("serializer", serializerDescriptor(typeParameters)),
                name,
            )
        }
    }

    @ParameterizedTest(name = "kotlinc {0}")
    @MethodSource("k2ReleaseKeys")
    fun `serializer() and generatedSerializer() beside KeepGeneratedSerializer are KOTLINX_SERIALIZATION`(key: String) {
        val members = entries(key, "SerKept\$Companion")

        assertEquals(MARKED, members.single { it.first == "serializer" }.third)
        val generatedSerializer = members.single { it.first.startsWith("generatedSerializer") }
        assertEquals("()$SERIALIZER", generatedSerializer.second)
        assertEquals(MARKED, generatedSerializer.third)
        assertEquals(
            isK1(key),
            generatedSerializer.first == "generatedSerializer",
            "only K1 writes the name without a module suffix: ${generatedSerializer.first}",
        )
    }

    @ParameterizedTest(name = "kotlinc {0}")
    @MethodSource("keys")
    fun `an adopter's companion members and the other members of a serializable class stay unmarked`(key: String) {
        val build = CompilerFixtures.kotlincSerialization(key)

        assertEquals(GeneratedBy.NONE, build.marks("SerNamed\$Factory").of("make", "()L${SELF}SerNamed;"))
        assertEquals(GeneratedBy.NONE, build.marks("SerFlag\$Companion").of("serializer", "(Z)Ljava/lang/String;"))
        assertEquals(GeneratedBy.NONE, build.marks("SerNot\$Companion").of("serializer", serializerDescriptor(0)))
        assertEquals(GeneratedBy.NONE, build.marks("SerNotWithSerializer\$Companion").of("serializer", serializerDescriptor(0)))
        assertEquals(GeneratedBy.NONE, build.marks("SerNotObject").of("serializer", serializerDescriptor(0)))
        assertEquals(GeneratedBy.NONE, build.marks("SerNestedHelper\$Helper").of("serializer", serializerDescriptor(0)))
        assertEquals(GeneratedBy.NONE, build.marks("SerFakeKept\$Companion").of("generatedSerializer", serializerDescriptor(0)))
        assertEquals(GeneratedBy.NONE, build.marks("SerOneParameter\$Companion").of("serializer", serializerDescriptor(1)))
        assertEquals(GeneratedBy.NONE, build.marks("SerHelperNamed\$Helper").of("serializer", serializerDescriptor(0)))
        assertEquals(GeneratedBy.NONE, build.marks("SerAnnotatedNot\$Companion").of("serializer", serializerDescriptor(0)))
        listOf(
            "SerJvmFieldHelper",
            "SerLateinitHelper",
            "SerConstHelper",
            "SerEnumHelper",
            "SerInterfaceHelper",
            "SerSealedHelper",
            "SerHiddenHelper",
            "SerDataHelper",
            "SerSerializableHelper",
        ).forEach { name ->
            assertEquals(GeneratedBy.NONE, build.marks("$name\$Helper").of("serializer", serializerDescriptor(0)), name)
        }
        listOf("SerPlain\$Companion", "SerBox\$Companion", "SerSingleton", "SerShape\$Companion").forEach { name ->
            build.markEntries(name).filter { it.first != "serializer" }.forEach { (member, descriptor, mark) ->
                assertEquals(GeneratedBy.NONE, mark, "$name.$member$descriptor")
            }
        }
        listOf("SerPlain", "SerBox", "SerNamed", "SerFlag", "SerNot", "SerExternal").forEach { name ->
            build.markEntries(name).forEach { (member, descriptor, mark) ->
                assertEquals(GeneratedBy.NONE, mark, "$name.$member$descriptor")
            }
        }
    }

    @ParameterizedTest(name = "kotlinc {0}")
    @MethodSource("keys")
    fun `an adopter's own serializer objects and classes are unmarked and are not unread shapes`(key: String) {
        val build = CompilerFixtures.kotlincSerialization(key)

        listOf("SerObjectSerializer", "SerClassSerializer").forEach { name ->
            build.markEntries(name).forEach { (member, descriptor, mark) ->
                assertEquals(GeneratedBy.NONE, mark, "$name.$member$descriptor")
            }
            assertEquals(emptyList(), build.unreadEntries(name), name)
        }
    }

    @ParameterizedTest(name = "kotlinc {0}")
    @MethodSource("keys")
    fun `a class-form lazy lambda the plugin builds is KOTLINX_SERIALIZATION, and K2 writes none`(key: String) {
        val build = CompilerFixtures.kotlincSerialization(key)
        // The plugin's annotation implementation for a meta-serializable class is synthetic, so the agent probes none of it.
        val lambdas = build.classNames().filter { LAMBDA_NAME.matches(it) && "\$annotationImpl\$" !in it }

        if (isK1(key)) {
            listOf("SerAbstract\$Companion\$1", "SerColor\$Companion\$1", "SerShape\$Companion\$1", "SerSingleton\$1").forEach {
                assertTrue(it in lambdas, "$it is among $lambdas")
            }
        } else {
            assertEquals(emptyList(), lambdas)
        }
        val adopterLambdas = lambdas.filter { it.startsWith("SerLazies\$Companion\$lazies\$") }
        adopterLambdas.forEach { name ->
            build.markEntries(name).forEach { (member, descriptor, mark) ->
                assertEquals(GeneratedBy.NONE, mark, "$name.$member$descriptor")
            }
        }
        (lambdas - adopterLambdas.toSet()).forEach { name ->
            val members = build.markEntries(name)
            assertTrue(members.any { it.first == "invoke" && it.second == "()$SERIALIZER" }, name)
            members.forEach { (member, descriptor, mark) -> assertEquals(MARKED, mark, "$name.$member$descriptor") }
        }
    }

    @ParameterizedTest(name = "kotlinc {0}")
    @MethodSource("k1ModeKeys")
    fun `the lazy lambdas of a class's child serializers are KOTLINX_SERIALIZATION where kotlinc writes them as classes`(key: String) {
        val build = CompilerFixtures.kotlincSerialization(key)

        listOf("SerHolder\$Companion\$\$childSerializers\$1", "SerHolder\$Companion\$\$childSerializers\$3").forEach { name ->
            build.markEntries(name).forEach { (member, descriptor, mark) -> assertEquals(MARKED, mark, "$name.$member$descriptor") }
        }
    }

    @ParameterizedTest(name = "kotlinc {0}")
    @MethodSource("k1Keys")
    fun `a serializer class the adopter declared by hand is unmarked, extra included`(key: String) {
        val build = CompilerFixtures.kotlincSerialization(key)

        assertEquals(GeneratedBy.NONE, build.marks("SerHandDeclared\$\$serializer").only("extra"))
        build.markEntries("SerHandDeclared\$\$serializer").forEach { (member, descriptor, mark) ->
            assertEquals(GeneratedBy.NONE, mark, "SerHandDeclared\$\$serializer.$member$descriptor")
        }
        assertEquals(MARKED, build.marks("SerHandDeclared\$Companion").of("serializer", serializerDescriptor(0)))
    }

    @ParameterizedTest(name = "kotlinc {0}")
    @MethodSource("keys")
    fun `the interface methods of a Serializer forClass object are an unread shape and no generated mark`(key: String) {
        val build = CompilerFixtures.kotlincSerialization(key)
        val unread = build.unreadEntries("SerExternalSerializer")

        assertEquals(
            setOf(
                "getDescriptor" to "()Lkotlinx/serialization/descriptors/SerialDescriptor;",
                "serialize" to "($ENCODER${'L'}${SELF}SerExternal;)V",
                "deserialize" to "($DECODER)${'L'}${SELF}SerExternal;",
            ),
            unread.map { it.first to it.second }.toSet(),
        )
        unread.forEach { assertEquals(UnreadShape.KOTLINX_SERIALIZER_OBJECT, it.third) }
        build.markEntries("SerExternalSerializer").forEach { (member, descriptor, mark) ->
            assertEquals(GeneratedBy.NONE, mark, "SerExternalSerializer.$member$descriptor")
        }
    }
}
