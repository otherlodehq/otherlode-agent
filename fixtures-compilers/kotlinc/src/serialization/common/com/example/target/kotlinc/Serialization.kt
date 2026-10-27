@file:OptIn(ExperimentalSerializationApi::class, InternalSerializationApi::class)

package com.example.target.kotlinc

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.MetaSerializable
import kotlinx.serialization.Serializable
import kotlinx.serialization.Serializer
import kotlinx.serialization.Transient
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

// Fixtures for the kotlinx.serialization plugin's output (ADR 0078). The plugin fills in each
// $serializer class, each companion's serializer() and the lazy cached serializer.

@Serializable
class SerPlain(
    val a: Int,
    val b: String,
)

@Serializable
data class SerData(
    val a: Int,
    val tags: List<String>,
)

@Serializable
class SerDefaults(
    val a: Int,
    val b: String = "x",
    @Transient val skipped: String = "t",
)

object SerObjectSerializer : KSerializer<SerWithObject> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("SerWithObject", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: SerWithObject,
    ) = encoder.encodeString(value.text)

    override fun deserialize(decoder: Decoder): SerWithObject = SerWithObject(decoder.decodeString())
}

@Serializable(with = SerObjectSerializer::class)
class SerWithObject(
    val text: String,
)

class SerClassSerializer : KSerializer<SerWithClass> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("SerWithClass", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: SerWithClass,
    ) = encoder.encodeString(value.text)

    override fun deserialize(decoder: Decoder): SerWithClass = SerWithClass(decoder.decodeString())
}

@Serializable(with = SerClassSerializer::class)
class SerWithClass(
    val text: String,
)

@Serializable
sealed class SerShape {
    @Serializable
    class Circle(
        val radius: Double,
    ) : SerShape()

    @Serializable
    class Square(
        val side: Double,
    ) : SerShape()
}

@Serializable
abstract class SerAbstract {
    abstract val size: Int
}

@Serializable
sealed interface SerSealedInterface

@Serializable
class SerSealedImpl(
    val q: Int,
) : SerSealedInterface

@Serializable
object SerSingleton

@Serializable
enum class SerColor { RED, GREEN }

@Serializable
class SerBox<T>(
    val value: T,
)

@Serializable
class SerPair<A, B>(
    val first: A,
    val second: B,
)

@Serializable
@JvmInline
value class SerValue(
    val raw: String,
)

@Serializable
class SerHolder(
    val color: SerColor,
    val shape: SerShape,
    val boxed: SerBox<Int>,
)

@Serializable
class SerNamed(
    val a: Int,
) {
    companion object Factory {
        fun make(): SerNamed = SerNamed(2)
    }
}

@Serializable
class SerFlag(
    val a: Int,
) {
    companion object {
        fun serializer(flag: Boolean): String = "mine $flag"
    }
}

class SerNot(
    val a: Int,
) {
    companion object {
        fun serializer(): KSerializer<Int> = Int.serializer()
    }
}

class SerExternal(
    val a: Int,
    val b: String,
)

@Serializer(forClass = SerExternal::class)
object SerExternalSerializer

@Serializable
sealed class SerSealedGeneric<T> {
    @Serializable
    class Leaf<T>(
        val v: T,
    ) : SerSealedGeneric<T>()
}

/** A serializable abstract class with a type parameter, whose `serializer()` builds a polymorphic serializer. */
@Serializable
abstract class SerAbstractGeneric<T>

/** A serializer the generic class below names in `with`, built from its type argument's serializer. */
class SerBoxSerializer<T>(
    private val inner: KSerializer<T>,
) : KSerializer<SerCustomBox<T>> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("SerCustomBox", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: SerCustomBox<T>,
    ) = inner.serialize(encoder, value.t)

    override fun deserialize(decoder: Decoder): SerCustomBox<T> = SerCustomBox(inner.deserialize(decoder))
}

@Serializable(with = SerBoxSerializer::class)
class SerCustomBox<T>(
    val t: T,
)

/** An annotation that makes the classes it marks serializable. */
@MetaSerializable
@Target(AnnotationTarget.CLASS)
annotation class SerMeta

@SerMeta
class SerMetaUser(
    val a: Int,
)

/** A companion property that holds a nested object whose own `serializer()` the adopter wrote. */
@Serializable
class SerNestedHelper(
    val a: Int,
) {
    companion object {
        val helper = Helper
    }

    object Helper {
        fun serializer(): KSerializer<SerNestedHelper> = SerNestedHelper.serializer()
    }
}

/** A class with child serializers whose companion keeps an array of the adopter's own lazy values. */
@Serializable
class SerLazies(
    val c: SerColor,
    val s: SerShape,
) {
    companion object {
        val lazies = arrayOf(lazy(LazyThreadSafetyMode.PUBLICATION) { SerColor.serializer() })
    }
}

/** A class that is not serializable, with a `serializer()` the plugin's descriptor matches. */
class SerNotWithSerializer {
    companion object {
        fun serializer(): KSerializer<SerPlain> = SerPlain.serializer()
    }
}

/** An object that is not serializable, with a `serializer()` the plugin's descriptor matches. */
object SerNotObject {
    fun serializer(): KSerializer<SerPlain> = SerPlain.serializer()
}

/** A serializable class whose companion has the adopter's own `generatedSerializer()`, with no `@KeepGeneratedSerializer`. */
@Serializable
class SerFakeKept(
    val a: Int,
) {
    companion object {
        fun generatedSerializer(): KSerializer<SerFakeKept> = serializer()
    }
}

/** A serializable class whose companion has the adopter's `serializer` with a parameter the plugin's does not take. */
@Serializable
class SerOneParameter(
    val a: Int,
) {
    companion object {
        fun serializer(unused: KSerializer<*>): KSerializer<SerOneParameter> = serializer()
    }
}

/** A nested class held in a companion property of its own name, with the adopter's own `serializer()`. */
@Serializable
class SerHelperNamed(
    val a: Int,
) {
    class Helper {
        fun serializer(): KSerializer<SerHelperNamed> = SerHelperNamed.serializer()
    }

    companion object {
        val Helper = Helper()
    }
}

/** A plain annotation, which does not make a class serializable. */
@Target(AnnotationTarget.CLASS)
annotation class SerMarker

/** A class with an annotation of this build that is not serializable, with a `serializer()` the plugin's descriptor matches. */
@SerMarker
class SerAnnotatedNot {
    companion object {
        fun serializer(): KSerializer<SerPlain> = SerPlain.serializer()
    }
}

/** A class that is not serializable whose companion is. */
class SerHost {
    @Serializable
    companion object
}

/** A nested class held in a `@JvmField` companion property of its own name, whose backing field is public. */
@Serializable
class SerJvmFieldHelper(
    val a: Int,
) {
    class Helper {
        fun serializer(): KSerializer<SerJvmFieldHelper> = SerJvmFieldHelper.serializer()
    }

    companion object {
        @JvmField
        val Helper = Helper()
    }
}

/** The same through a `lateinit` companion property, whose backing field is public. */
@Serializable
class SerLateinitHelper(
    val a: Int,
) {
    class Helper {
        fun serializer(): KSerializer<SerLateinitHelper> = SerLateinitHelper.serializer()
    }

    companion object {
        @Suppress("ktlint:standard:property-naming")
        lateinit var Helper: Helper
    }
}

/** A constant of the same name as a nested class, whose field is an `int`. */
@Serializable
class SerConstHelper(
    val a: Int,
) {
    class Helper {
        fun serializer(): KSerializer<SerConstHelper> = SerConstHelper.serializer()
    }

    companion object {
        @Suppress("ktlint:standard:property-naming")
        const val Helper = 1
    }
}

/** A serializable class with a private companion. */
@Serializable
class SerPrivateCompanion(
    val a: Int,
) {
    private companion object
}

/** An enum held in a companion property of its own name, with the adopter's own `serializer()`. */
@Serializable
class SerEnumHelper(
    val a: Int,
) {
    enum class Helper {
        ONE,
        ;

        fun serializer(): KSerializer<SerEnumHelper> = SerEnumHelper.serializer()
    }

    companion object {
        val Helper = SerEnumHelper.Helper.ONE
    }
}

/** An interface held in a `@JvmField` companion property of its own name. */
@Serializable
class SerInterfaceHelper(
    val a: Int,
) {
    interface Helper {
        fun serializer(): KSerializer<SerInterfaceHelper> = SerInterfaceHelper.serializer()
    }

    class HelperImpl : Helper

    companion object {
        @JvmField
        val Helper: Helper = HelperImpl()
    }
}

/** A sealed class held in a companion property of its own name. */
@Serializable
class SerSealedHelper(
    val a: Int,
) {
    sealed class Helper {
        fun serializer(): KSerializer<SerSealedHelper> = SerSealedHelper.serializer()

        object Only : Helper()
    }

    companion object {
        val Helper: Helper = SerSealedHelper.Helper.Only
    }
}

/** A generic class that is not serializable whose companion is: the companion's `serializer()` takes no type serializers. */
class SerGenericHost<T> {
    @Serializable
    companion object
}

/** A class with a private constructor and a hidden one, held in a companion property of its own name. */
@Serializable
class SerHiddenHelper(
    val a: Int,
) {
    class Helper private constructor() {
        @Deprecated("hidden", level = DeprecationLevel.HIDDEN)
        constructor(unused: Int) : this()

        fun serializer(): KSerializer<SerHiddenHelper> = SerHiddenHelper.serializer()

        companion object {
            fun make() = Helper()
        }
    }

    companion object {
        val Helper: SerHiddenHelper.Helper = SerHiddenHelper.Helper.make()
    }
}

/** A data class with only private constructors, held in a companion property of its own name. */
@Serializable
class SerDataHelper(
    val a: Int,
) {
    data class Helper private constructor(
        val x: Int,
    ) {
        private constructor() : this(0)

        fun serializer(): KSerializer<SerDataHelper> = SerDataHelper.serializer()

        companion object {
            fun make() = Helper()
        }
    }

    companion object {
        val Helper: SerDataHelper.Helper = SerDataHelper.Helper.make()
    }
}

/** A serializable class with only private constructors, held in a companion property of its own name. */
@Serializable
class SerSerializableHelper(
    val a: Int,
) {
    @Serializable
    class Helper private constructor(
        val y: Int,
    ) {
        private constructor() : this(0)

        fun serializer(): KSerializer<SerSerializableHelper> = SerSerializableHelper.serializer()

        companion object {
            fun make() = Helper()
        }
    }

    companion object {
        val Helper: SerSerializableHelper.Helper = SerSerializableHelper.Helper.make()
    }
}
