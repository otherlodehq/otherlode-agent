package com.example.target.kotlinc

/** An interface whose members cover every parameter and return shape a delegation forwarder takes. */
interface Shaped {
    fun noArgs(): String

    fun smalls(a: Int, b: Boolean, c: Char, d: Byte, e: Short): Int

    fun wides(l: Long, d: Double, f: Float): Long

    fun nonNull(text: String): String

    fun nullable(text: String?): String?

    fun unitReturn()

    fun unitWithArg(text: String)

    fun <T> generic(item: T): T

    fun list(items: List<String>): List<String>

    fun primitiveReturn(): Int

    fun mixed(a: String, b: String?, c: Int, d: List<String>): String

    val title: String

    var level: Int

    var label: String

    suspend fun fetch(id: Int): String

    suspend fun fetchUnit(text: String)
}

/** Delegates every member of [Shaped]. */
class AllDelegated(
    inner: Shaped,
) : Shaped by inner

/** A second interface, delegated beside [Shaped]. */
interface Other {
    fun other(text: String): String

    val otherName: String
}

/** Delegates two interfaces. */
class TwoDelegates(
    first: Shaped,
    second: Other,
) : Shaped by first,
    Other by second

/** Delegates through a private constructor property, whose field kotlinc reuses, and overrides one member by hand. */
class OverridesOne(
    private val inner: Shaped,
) : Shaped by inner {
    override fun noArgs(): String = inner.noArgs()
}

/** Overrides one member with its own code and delegates the rest. */
class OverridesWithCode(
    inner: Shaped,
) : Shaped by inner {
    override fun nonNull(text: String): String = "own " + text
}

/** An interface with default members and an abstract one. */
interface WithDefaults {
    fun abstractOne(): String

    fun defaultOne(text: String): String = text + abstractOne()

    fun defaultPlain(): String = "plain"

    val defaultTitle: String
        get() = "title"
}

/** Delegates [WithDefaults]; its default members are not overridden. */
class DelegatesDefaults(
    inner: WithDefaults,
) : WithDefaults by inner

/** Delegates [WithDefaults] and overrides a default member by hand. */
class DelegatesAndOverridesDefault(
    inner: WithDefaults,
) : WithDefaults by inner {
    override fun defaultPlain(): String = "overridden"
}

/** A value class over a string. */
@JvmInline
value class Token(
    val raw: String,
)

/** A value class over an int. */
@JvmInline
value class Count(
    val n: Int,
)

/** An interface with value-class parameters and returns. */
interface Valued {
    fun takeToken(t: Token): Int

    fun takeCount(c: Count): Int

    fun giveToken(): Token

    fun giveCount(): Count

    fun giveNullableToken(): Token?

    val tokenProp: Token
}

/** Delegates [Valued]. */
class DelegatesValued(
    inner: Valued,
) : Valued by inner

/** An abstract class that delegates, so the forwarders sit in the abstract class. */
abstract class AbstractDelegating(
    inner: Other,
) : Other by inner

/** A generic interface delegated at a concrete type. */
interface Feed<T> {
    fun get(): T

    fun put(item: T): Boolean
}

/** Delegates [Feed] at `String`, where the result is cast. */
class StringFeed(
    inner: Feed<String>,
) : Feed<String> by inner

/** Delegates [Feed] at `Int`, where the forwarder boxes its argument and casts its result. */
class IntFeed(
    inner: Feed<Int>,
) : Feed<Int> by inner

/** A default companion whose `@JvmStatic` members cover each parameter and return shape. */
class WithCompanion {
    fun instanceMethod(): String = "instance"

    companion object {
        @JvmStatic
        fun noArgs(): String = "no args"

        @JvmStatic
        fun smalls(a: Int, b: Boolean): Int = if (b) a else -a

        @JvmStatic
        fun wides(l: Long, d: Double): Long = l + d.toLong()

        @JvmStatic
        fun nonNull(text: String): String = text + "!"

        @JvmStatic
        fun nullable(text: String?): String? = text

        @JvmStatic
        fun unitReturn() {
            println("unit")
        }

        @JvmStatic
        fun <T> generic(item: T): T = item

        @JvmStatic
        val staticValue: String = "static value"

        @JvmStatic
        var staticVar: Int = 0

        @JvmStatic
        val computed: String
            get() = "computed " + staticValue

        @JvmStatic
        fun withBranch(n: Int): String = if (n > 0) "pos" else "neg"

        @JvmStatic
        fun valued(t: Token): Int = t.raw.length

        @JvmStatic
        fun givesToken(): Token = Token("x")

        @JvmStatic
        suspend fun suspending(id: Int): String = "id" + id

        fun notStatic(): String = "not static"
    }
}

/** A named companion. */
class WithNamedCompanion {
    companion object Factory {
        @JvmStatic
        fun create(text: String): String = text

        @JvmStatic
        fun none(): String = "none"
    }
}

/** An object, where `@JvmStatic` puts the body in the static method itself. */
object PlainObjectStatics {
    @JvmStatic
    fun noArgs(): String = "no args"

    @JvmStatic
    fun nonNull(text: String): String = text + "!"
}

/** An interface companion with a `@JvmStatic` function. */
interface WithInterfaceCompanion {
    companion object {
        @JvmStatic
        fun make(text: String): String = text
    }
}

/** A `@JvmStatic` companion function renamed with `@JvmName`. */
class RenamedStatic {
    companion object {
        @JvmStatic
        @JvmName("renamed")
        fun original(text: String): String = text
    }
}

/** A private `@JvmStatic` companion function, which a static calls through an accessor. */
class PrivateStatic {
    companion object {
        @JvmStatic
        private fun hidden(text: String): String = text

        fun exposed(text: String): String = hidden(text)
    }
}

/** A `@JvmStatic` companion function with `@JvmOverloads`. */
class OverloadedStatic {
    companion object {
        @JvmStatic
        @JvmOverloads
        fun two(a: Int, b: String = "b"): String = b + a
    }
}

/** A sub-interface, to see which owner a forwarder for an inherited member names. */
interface SubOther : Other {
    fun extra(): String
}

/** Delegates [SubOther], whose [Other] members are inherited. */
class DelegatesSub(
    inner: SubOther,
) : SubOther by inner

/** Delegates through a public constructor property, whose field kotlinc reuses as the delegate. */
class DelegatesPublicProperty(
    val inner: Other,
) : Other by inner

/** Delegates to an object expression, so the delegate field has a class type. */
class DelegatesExpression : Other by object : Other {
    override fun other(text: String): String = text

    override val otherName: String = "n"
}

/** Delegates [Other] through a property typed as the sub-interface, so the field's type is not a direct interface. */
class ViaSub(
    private val inner: SubOther,
) : Other by inner
