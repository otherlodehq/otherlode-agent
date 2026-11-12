@file:Suppress("ktlint:standard:function-naming")

package com.example.target.kotlinc

interface Greeting {
    fun greet(name: String): String = "hi $name"
}

@JvmInline
value class GreetingByInt(
    val v: Int,
) : Greeting

@JvmInline
value class GreetingByString(
    val v: String,
) : Greeting

interface LabelTags {
    fun tag(l: Label): String = l.s
}

@JvmInline
value class TaggedId(
    val v: Int,
) : LabelTags

interface LabelMaker {
    fun make(): Label = Label("x")
}

@JvmInline
value class MakerId(
    val v: Int,
) : LabelMaker

interface IdTaker {
    fun take(i: Id): Int = i.v
}

@JvmInline
value class IdTakerLabel(
    val v: String,
) : IdTaker

interface Source<T> {
    fun get(): T? = null
}

@JvmInline
value class StringSource(
    val v: Int,
) : Source<String>

@JvmInline
value class LabelSource(
    val v: Int,
) : Source<Label>

interface Echoing<T> {
    fun echo(t: T): T = t
}

@JvmInline
value class StringEchoing(
    val v: Int,
) : Echoing<String>

@JvmInline
value class IntEchoing(
    val v: Int,
) : Echoing<Int>

@JvmInline
value class IdEchoing(
    val v: Int,
) : Echoing<Id>

interface Spread<T : CharSequence> {
    fun m(
        a: String,
        b: String?,
        c: Long,
        d: T,
        e: Label,
    ): String = a
}

@JvmInline
value class SpreadValue(
    val v: Long,
) : Spread<String>

interface Holds<T> {
    fun get(): T? = null

    fun put(t: T): T = t

    val item: T? get() = null
}

interface Takes<T> {
    fun take(t: T?): Int = if (t == null) 0 else 1
}

@JvmInline
value class ItemOwner(
    val v: Int,
) : Holds<String>

@JvmInline
value class LabelTakesValue(
    val v: Int,
) : Takes<Label>

class LabelHolds : Holds<Label>

class LabelTakes : Takes<Label>

interface LabelHoldsApi : Holds<Label>

class LabelHoldsApiImpl : LabelHoldsApi

interface LabelTakesApi : Takes<Label>

class LabelTakesApiImpl : LabelTakesApi

interface Mid<T : CharSequence> {
    fun f(t: T): Int = t.length
}

class MidOfString : Mid<String>

interface MidReturns<T : CharSequence> {
    fun pick(t: T): T = t
}

class MidReturnsString : MidReturns<String>

interface NumberMid<T : Number> {
    fun f(t: T): Int = t.toInt()
}

class IntMid : NumberMid<Int>

@JvmInline
value class MidValue(
    val v: Int,
) : Mid<String>

interface MidApi : Mid<String>

class MidApiImpl : MidApi

interface MidNullable<T : CharSequence?> {
    fun f(t: T): Int = t?.length ?: 0
}

class MidNullableOfString : MidNullable<String?>

open class OverrideMidNullable : MidNullable<String?> {
    override fun f(t: String?): Int = super.f(t)
}

open class OverloadMidNullable : MidNullable<CharSequence?> {
    open fun f(t: String?): Int = super.f(t)
}

open class LabelHoldsOverride : Holds<Label> {
    override fun get(): Label? = super.get()

    override val item: Label? get() = super.item
}

open class LabelTakesOverride : Takes<Label> {
    override fun take(t: Label?): Int = super.take(t)
}

@JvmInline
value class MemberCalls(
    val v: Int,
) : Greeting,
    LabelTags,
    Echoing<String> {
    fun greetAgain(name: String): String = greet(name)

    private fun greetPrivately(name: String): String = greet(name)

    fun tagAgain(l: Label): String = tag(l)

    fun echoAgain(t: String): String = echo(t)
}

@JvmInline
value class OverrideGreeting(
    val v: Int,
) : Greeting {
    override fun greet(name: String): String = super.greet(name)
}

@JvmInline
value class OverrideTags(
    val v: Int,
) : LabelTags {
    override fun tag(l: Label): String = super.tag(l)
}

@JvmInline
value class OverrideEchoing(
    val v: Int,
) : Echoing<String> {
    override fun echo(t: String): String = super.echo(t)
}

@JvmInline
value class BacktickGreeting(
    val v: Int,
) : Greeting {
    fun `greet-impl`(name: String): String = greet(name)
}

interface PairOf<T> {
    fun m(
        a: String,
        t: T,
    ): T = t
}

interface Strict {
    fun m(
        a: String,
        t: Label,
    ): Label?
}

@JvmInline
value class Adapter(
    val v: Int,
) : Strict,
    PairOf<Label?> {
    @Suppress("UNCHECKED_CAST")
    override fun m(
        a: String,
        t: Label,
    ): Label? = (this as PairOf<Label?>).m(a, t)
}

interface TakesPair<T> {
    fun take(
        a: String,
        t: T?,
    ): Int = if (t == null) 0 else 1
}

interface StrictTake {
    fun take(
        a: String,
        t: Label,
    ): Int
}

@JvmInline
value class TakeAdapter(
    val v: Int,
) : StrictTake,
    TakesPair<Label> {
    override fun take(
        a: String,
        t: Label,
    ): Int = (this as TakesPair<Label>).take(a, t)
}

@JvmInline
value class OverrideSelf(
    val v: Int,
) : Holds<String> {
    override fun put(t: String): String = (this as Holds<String>).put(t)
}

@JvmInline
value class OverloadPair(
    val v: Int,
) : PairOf<Label> {
    fun m(
        a: String,
        t: Label?,
    ): Label? = (this as PairOf<Label?>).m(a, t)
}

@JvmInline
value class PrivateOverloadPair(
    val v: Int,
) : PairOf<Label> {
    private fun m(
        a: String,
        t: Label?,
    ): Label? = (this as PairOf<Label?>).m(a, t)
}
