package com.example.target.kotlinc

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

class AllDelegated(
    inner: Shaped,
) : Shaped by inner

interface Other {
    fun other(text: String): String

    val otherName: String
}

class TwoDelegates(
    first: Shaped,
    second: Other,
) : Shaped by first,
    Other by second

class OverridesOne(
    private val inner: Shaped,
) : Shaped by inner {
    override fun noArgs(): String = inner.noArgs()
}

class OverridesWithCode(
    inner: Shaped,
) : Shaped by inner {
    override fun nonNull(text: String): String = "own " + text
}

interface WithDefaults {
    fun abstractOne(): String

    fun defaultOne(text: String): String = text + abstractOne()

    fun defaultPlain(): String = "plain"

    val defaultTitle: String
        get() = "title"
}

class DelegatesDefaults(
    inner: WithDefaults,
) : WithDefaults by inner

class DelegatesAndOverridesDefault(
    inner: WithDefaults,
) : WithDefaults by inner {
    override fun defaultPlain(): String = "overridden"
}

@JvmInline
value class Token(
    val raw: String,
)

@JvmInline
value class Count(
    val n: Int,
)

interface Valued {
    fun takeToken(t: Token): Int

    fun takeCount(c: Count): Int

    fun giveToken(): Token

    fun giveCount(): Count

    fun giveNullableToken(): Token?

    val tokenProp: Token
}

class DelegatesValued(
    inner: Valued,
) : Valued by inner

abstract class AbstractDelegating(
    inner: Other,
) : Other by inner

interface Feed<T> {
    fun get(): T

    fun put(item: T): Boolean
}

class StringFeed(
    inner: Feed<String>,
) : Feed<String> by inner

class IntFeed(
    inner: Feed<Int>,
) : Feed<Int> by inner

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

class WithNamedCompanion {
    companion object Factory {
        @JvmStatic
        fun create(text: String): String = text

        @JvmStatic
        fun none(): String = "none"
    }
}

object PlainObjectStatics {
    @JvmStatic
    fun noArgs(): String = "no args"

    @JvmStatic
    fun nonNull(text: String): String = text + "!"
}

interface WithInterfaceCompanion {
    companion object {
        @JvmStatic
        fun make(text: String): String = text
    }
}

class RenamedStatic {
    companion object {
        @JvmStatic
        @JvmName("renamed")
        fun original(text: String): String = text
    }
}

class PrivateStatic {
    companion object {
        @JvmStatic
        private fun hidden(text: String): String = text

        fun exposed(text: String): String = hidden(text)
    }
}

class OverloadedStatic {
    companion object {
        @JvmStatic
        @JvmOverloads
        fun two(a: Int, b: String = "b"): String = b + a
    }
}

interface SubOther : Other {
    fun extra(): String
}

class DelegatesSub(
    inner: SubOther,
) : SubOther by inner

class DelegatesPublicProperty(
    val inner: Other,
) : Other by inner

class DelegatesExpression : Other by object : Other {
    override fun other(text: String): String = text

    override val otherName: String = "n"
}

class ViaSub(
    private val inner: SubOther,
) : Other by inner
