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

@JvmInline
value class Shout(
    val s: String,
) {
    override fun toString(): String = s.uppercase()

    fun twice(): String = s + s
}

@JvmInline
value class Rank(
    val v: Int,
) : Comparable<Rank> {
    override fun compareTo(other: Rank): Int = v.compareTo(other.v)
}

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

interface SuspendDefaultOverride : SuspendDefault {
    override suspend fun run() = super.run()
}

class OverloadById : ValueStore<Any> {
    fun put(t: Id): Any = super.put(t)
}

class OverloadDropsResult : ValueStore<Any> {
    fun put(t: Int) {
        super.put(t)
    }
}

class OverloadBoxesInt : ValueStore<Any> {
    fun put(t: Int): Any = super.put(t)
}

interface OverloadByIdApi : ValueStore<Any> {
    fun put(t: Id): Any = super.put(t)
}

interface OverloadDropsResultApi : ValueStore<Any> {
    fun put(t: Int) {
        super.put(t)
    }
}

interface Greets {
    fun greet(): String = "hi"
}

@JvmInline
value class GreetingId(
    val v: Int,
) : Greets

class EnclosingStore<X : CharSequence> {
    inner class Inner : ValueStore<X>
}

interface MidStore<T> : ValueStore<T>

class EnclosingMidStore<X : CharSequence> {
    inner class Inner : MidStore<X>
}

fun <X : CharSequence> anonymousStore(): ValueStore<X> = object : ValueStore<X> {}

fun <X : Number> localStore(): Any {
    class LocalStore : ValueStore<X>
    return LocalStore()
}

interface StoreAndLog<T> {
    fun put(t: T): T = t

    fun log(a: Any?): Any? = a
}

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

class OverloadByString : ValueStore<Any> {
    fun put(t: String): Any = super.put(t)
}

class NullableIntOverload : ValueStore<Int?> {
    fun put(t: Int): Int? = super.put(t)
}

class NullableIdOverload : ValueStore<Id?> {
    fun put(t: Id): Id? = super.put(t)
}

interface NullableIntOverloadApi : ValueStore<Int?> {
    fun put(t: Int): Int? = super.put(t)
}

inline fun <reified X : CharSequence> inlinedStore(): ValueStore<X> = object : ValueStore<X> {}

fun callsInlinedStore(): Any = inlinedStore<String>()

class OverrideAndOverload : ValueStore<Int?> {
    override fun put(t: Int?): Int? = t ?: 0

    fun put(t: Int): Int? = super.put(t)
}

abstract class AbstractOverrideAndOverload : ValueStore<Id?> {
    abstract override fun put(t: Id?): Id?

    fun put(t: Id): Id? = super.put(t)
}

interface OverrideAndOverloadApi : ValueStore<Int?> {
    override fun put(t: Int?): Int? = t ?: 0

    fun put(t: Int): Int? = super.put(t)
}

class GenericBoundOverload : ValueStore<Id> {
    fun <R : Id> put(r: R): Id? = super.put(r)
}
