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

@Serializable
abstract class SerAbstractGeneric<T>

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

@MetaSerializable
@Target(AnnotationTarget.CLASS)
annotation class SerMeta

@SerMeta
class SerMetaUser(
    val a: Int,
)

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

@Serializable
class SerLazies(
    val c: SerColor,
    val s: SerShape,
) {
    companion object {
        val lazies = arrayOf(lazy(LazyThreadSafetyMode.PUBLICATION) { SerColor.serializer() })
    }
}

class SerNotWithSerializer {
    companion object {
        fun serializer(): KSerializer<SerPlain> = SerPlain.serializer()
    }
}

object SerNotObject {
    fun serializer(): KSerializer<SerPlain> = SerPlain.serializer()
}

@Serializable
class SerFakeKept(
    val a: Int,
) {
    companion object {
        fun generatedSerializer(): KSerializer<SerFakeKept> = serializer()
    }
}

@Serializable
class SerOneParameter(
    val a: Int,
) {
    companion object {
        fun serializer(unused: KSerializer<*>): KSerializer<SerOneParameter> = serializer()
    }
}

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

@Target(AnnotationTarget.CLASS)
annotation class SerMarker

@SerMarker
class SerAnnotatedNot {
    companion object {
        fun serializer(): KSerializer<SerPlain> = SerPlain.serializer()
    }
}

class SerHost {
    @Serializable
    companion object
}

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

@Serializable
class SerPrivateCompanion(
    val a: Int,
) {
    private companion object
}

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

class SerGenericHost<T> {
    @Serializable
    companion object
}

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
