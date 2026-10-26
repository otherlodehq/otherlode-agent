package com.example.target.kotlinc

@JvmInline
value class Id(
    val v: Int,
)

@JvmInline
value class Label(
    val s: String,
)

@JvmInline
value class Wide(
    val l: Long,
)

/** A value class whose `toString` is the adopter's and which declares a member of its own. */
@JvmInline
value class Shout(
    val s: String,
) {
    override fun toString(): String = s.uppercase()

    fun twice(): String = s + s
}

/** A value class with an interface override, which kotlinc mangles and forwards to a static. */
@JvmInline
value class Rank(
    val v: Int,
) : Comparable<Rank> {
    override fun compareTo(other: Rank): Int = v.compareTo(other.v)
}

/** A value class whose `init` block goes into `constructor-impl`. */
@JvmInline
value class Checked(
    val v: Int,
) {
    init {
        require(v >= 0)
    }
}

data class IdAndCount(
    val id: Id,
    val n: Int,
)

data class IdLabelCount(
    val a: Id,
    val b: Label,
    val n: Int,
)

data class MaybeId(
    val a: Id?,
    val n: Int,
)

/** A plain class that writes the data-class names itself. */
class PlainParts(
    val a: Int,
    val b: String,
) {
    fun component1(): Int = a

    fun component2(): String = b

    fun copy(
        a: Int = this.a,
        b: String = this.b,
    ): PlainParts = PlainParts(a, b)

    override fun equals(other: Any?): Boolean = other is PlainParts && other.a == a

    override fun hashCode(): Int = a

    override fun toString(): String = "PlainParts"
}

/** A plain class whose hand-written members take a value class, so kotlinc mangles their names. */
class PlainMangledParts(
    val id: Id,
    val n: Int,
) {
    fun component1(): Id = id

    fun component2(): Int = n

    fun copy(
        id: Id = this.id,
        n: Int = this.n,
    ): PlainMangledParts = PlainMangledParts(id, n)

    override fun equals(other: Any?): Boolean = other is PlainMangledParts && other.n == n

    override fun hashCode(): Int = n

    override fun toString(): String = "PlainMangledParts"
}

interface ValueStore<T> {
    fun put(t: T): T = t

    fun get(): T? = null
}

class ValueStoreOfId : ValueStore<Id>

class ValueStoreOfUnit : ValueStore<Unit>

interface IdStoreApi : ValueStore<Id>

interface UnitStoreApi : ValueStore<Unit>

class IdStoreApiImpl : IdStoreApi

class UnitStoreApiImpl : UnitStoreApi

/** Overrides a default only to call `super`, which adds a null check the stub rule leaves unmarked. */
class UnitStoreOverride : ValueStore<Unit> {
    override fun put(t: Unit) = super.put(t)
}

interface SuspendDefault {
    suspend fun run() {
        println("x")
    }
}

interface SuspendDefaultSub : SuspendDefault

class SuspendDefaultSubImpl : SuspendDefaultSub

/** Overrides a suspend default only to call `super`: the same tail, with no receiver cast. */
interface SuspendDefaultOverride : SuspendDefault {
    override suspend fun run() = super.run()
}

/** An overload the adopter wrote that boxes a value-class argument for a `super` call, as a stub would. */
class OverloadById : ValueStore<Any> {
    fun put(t: Id): Any = super.put(t)
}

/** An overload the adopter wrote that drops the `super` call's result, as a stub at `Unit` would. */
class OverloadDropsResult : ValueStore<Any> {
    fun put(t: Int) {
        super.put(t)
    }
}

/** An overload the adopter wrote that boxes a primitive for a `super` call, as a stub at `Int` would. */
class OverloadBoxesInt : ValueStore<Any> {
    fun put(t: Int): Any = super.put(t)
}

/** The overload of [OverloadById] in a sub-interface, which under `disable` sits in its `$DefaultImpls`. */
interface OverloadByIdApi : ValueStore<Any> {
    fun put(t: Id): Any = super.put(t)
}

/** The overload of [OverloadDropsResult] in a sub-interface. */
interface OverloadDropsResultApi : ValueStore<Any> {
    fun put(t: Int) {
        super.put(t)
    }
}

interface Greets {
    fun greet(): String = "hi"
}

/** A value class that inherits [Greets.greet] without overriding it. */
@JvmInline
value class GreetingId(
    val v: Int,
) : Greets

/** A stub whose type argument is a variable the outer class declares, bounded by `CharSequence`. */
class EnclosingStore<X : CharSequence> {
    inner class Inner : ValueStore<X>
}

/** The same through an intermediate generic interface. */
interface MidStore<T> : ValueStore<T>

class EnclosingMidStore<X : CharSequence> {
    inner class Inner : MidStore<X>
}

/** An anonymous object whose type argument is a variable the enclosing function declares. */
fun <X : CharSequence> anonymousStore(): ValueStore<X> = object : ValueStore<X> {}

/** A local class whose type argument is a variable the enclosing function declares. */
fun <X : Number> localStore(): Any {
    class LocalStore : ValueStore<X>
    return LocalStore()
}

interface StoreAndLog<T> {
    fun put(t: T): T = t

    fun log(a: Any?): Any? = a
}

/** Overloads of `log` whose types match a type argument of the class but not `log`'s own parameter. */
class LogOverloadById : StoreAndLog<Id> {
    fun log(a: Id): Any? = super.log(a)
}

class LogOverloadByInt : StoreAndLog<Int> {
    fun log(a: Int): Any? = super.log(a)
}

class LogOverloadByString : StoreAndLog<String> {
    fun log(a: String?): Any? = super.log(a)
}

interface LogOverloadApi : StoreAndLog<Id> {
    fun log(a: Id): Any? = super.log(a)
}

/** An overload of `put` with a reference parameter, beside `put(t: T)` at `Any`. */
class OverloadByString : ValueStore<Any> {
    fun put(t: String): Any = super.put(t)
}

/** An overload taking `Int` beside the real stub at `Int?`, whose generic signature reads as at `Int`. */
class NullableIntOverload : ValueStore<Int?> {
    fun put(t: Int): Int? = super.put(t)
}

/** An overload taking the value class beside the real stub at `Id?`. */
class NullableIdOverload : ValueStore<Id?> {
    fun put(t: Id): Id? = super.put(t)
}

/** The overload of [NullableIntOverload] in a sub-interface. */
interface NullableIntOverloadApi : ValueStore<Int?> {
    fun put(t: Int): Int? = super.put(t)
}

/** An object kotlinc regenerates at each call site, with `String` in its signatures and `CharSequence` in its descriptors. */
inline fun <reified X : CharSequence> inlinedStore(): ValueStore<X> = object : ValueStore<X> {}

fun callsInlinedStore(): Any = inlinedStore<String>()

/** Overrides the default with a body and adds an overload that boxes for `super`, so no stub sits beside it. */
class OverrideAndOverload : ValueStore<Int?> {
    override fun put(t: Int?): Int? = t ?: 0

    fun put(t: Int): Int? = super.put(t)
}

/** The same with the default left abstract. */
abstract class AbstractOverrideAndOverload : ValueStore<Id?> {
    abstract override fun put(t: Id?): Id?

    fun put(t: Id): Id? = super.put(t)
}

/** The same in a sub-interface, whose `$DefaultImpls` holds the overload's body. */
interface OverrideAndOverloadApi : ValueStore<Int?> {
    override fun put(t: Int?): Int? = t ?: 0

    fun put(t: Int): Int? = super.put(t)
}

/** A generic overload whose bound erases to the value class's underlying type, as the stub's parameter does. */
class GenericBoundOverload : ValueStore<Id> {
    fun <R : Id> put(r: R): Id? = super.put(r)
}
