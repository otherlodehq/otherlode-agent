package com.example.target.kotlinc

/**
 * An interface whose default methods include one with a non-null reference parameter, so its
 * `$DefaultImpls` forwarder starts with kotlinc's null check, and one with a nullable parameter,
 * one with none and a property getter.
 */
interface Describer {
    fun id(): String

    fun describe(text: String): String = text + id()

    fun maybe(text: String?): String = "" + text + id()

    fun plain(): String = "plain " + id()

    val title: String
        get() = "title " + id()
}

/** A final class implementing [Describer], so kotlinc adds a stub per default method under `-jvm-default=disable`. */
class DescriberImpl : Describer {
    override fun id(): String = "impl"
}

/**
 * Implements [Describer], so the stub rule reads its methods, and holds an ordinary method whose
 * body only forwards to a hand-written function.
 */
class HandForwarder : Describer {
    override fun id(): String = "hand"

    fun forwardOnly(s: String): String = helper(s)

    private fun helper(s: String): String = s + "!"
}

/** Calls the stubs on [DescriberImpl], so the call edges can be read. */
class DescriberCaller {
    fun call(impl: DescriberImpl): String = impl.plain() + impl.describe("x")
}

/** Extends [Describer] without overriding anything, to see which class gets the stubs. */
interface SubDescriber : Describer

/** Implements [Describer] only through [SubDescriber]. */
class SubDescriberImpl : SubDescriber {
    override fun id(): String = "sub"
}

/** A generic interface whose defaults take and return its type parameter. */
interface Store<T> {
    fun load(): T? = null

    fun save(item: T): String = "saved " + item

    fun count(n: Int): Long = n.toLong()
}

/**
 * Implements [Store] at `String`, so under `-jvm-default=disable` its stubs take and return
 * `String` while the `$DefaultImpls` methods they call take and return `Object`.
 */
class StringStore : Store<String>

/** A generic interface whose default takes its type parameter, implemented below at a primitive type. */
interface Tally<T> {
    fun add(item: T): String = "added " + item
}

/**
 * Implements [Tally] at `Int`, so under `-jvm-default=disable` its stub takes an `int` and boxes it
 * with `Integer.valueOf` before calling the `$DefaultImpls` method, which takes `Object`.
 */
class IntTally : Tally<Int>

/** An open class implementing [Describer], whose stubs a subclass can override. */
open class OpenDescriber : Describer {
    override fun id(): String = "open"

    /** Calls this class's own stub, as a template method does. */
    fun own(): String = plain()
}

/** Inherits [OpenDescriber]'s stubs without overriding any. */
class PlainDescriber : OpenDescriber() {
    /** Calls a stub it only inherits. */
    fun inherited(): String = plain()
}

/** Calls a stub through a class that only inherits it. */
class PlainDescriberCaller {
    fun call(describer: PlainDescriber): String = describer.plain()
}

/** Overrides one of [OpenDescriber]'s stubs, so a virtual call to it can run this. */
class LoudDescriber : OpenDescriber() {
    override fun plain(): String = "PLAIN"
}

/** Calls a stub on [OpenDescriber] virtually. */
class OpenDescriberCaller {
    fun call(describer: OpenDescriber): String = describer.plain()
}

/**
 * Overrides a default only to call `super`, which under `-jvm-default=disable` compiles to exactly
 * the stub's body.
 */
class SuperDescriber : Describer {
    override fun id(): String = "super"

    override fun plain(): String = super.plain()
}

/** Calls a default through the interface type. */
class InterfaceCaller {
    fun call(describer: Describer): String = describer.plain()
}

/** An open class two levels below the one that declares the stubs. */
open class MidDescriber : OpenDescriber()

/** Three levels below [OpenDescriber], overriding nothing. */
class DeepDescriber : MidDescriber()

/** Calls through [DeepDescriber], a stub it inherits from two levels up and a method it inherits that is not a stub. */
class DeepDescriberCaller {
    fun call(describer: DeepDescriber): String = describer.plain()

    fun callOrdinary(describer: DeepDescriber): String = describer.id()
}

/** Inherits from a class outside the include rules. */
class InsideDescriber : com.example.outside.OutsideDescriber()

/** Calls a stub that [InsideDescriber] inherits from outside the include rules. */
class InsideDescriberCaller {
    fun call(describer: InsideDescriber): String = describer.plain()
}

/** Calls the stub it inherits through `super`, which is not a virtual call. */
class SuperCallingDescriber : OpenDescriber() {
    fun viaSuper(): String = super.plain()
}

/** Fixes [Store]'s type argument, so its `$DefaultImpls` forwarders pass `String` on as `Object`. */
interface StringStoreApi : Store<String>

/** Fixes [Tally]'s type argument at a primitive, so its `$DefaultImpls` forwarder boxes. */
interface IntTallyApi : Tally<Int>

/** A generic interface whose defaults return the type parameter and suspend with it. */
interface Echo<T> {
    fun echo(item: T): T = item

    suspend fun later(item: T): String = "later " + item
}

/**
 * Fixes [Echo]'s type argument at `Int`: its `$DefaultImpls` forwarders box the `int` result of the
 * interface's accessor from kotlinc 2.2, and box the suspend argument with `Boxing.boxInt` before.
 */
interface IntEcho : Echo<Int>

/** Implements [Echo] at `Int`, for the stubs' boxing in the same shapes. */
class IntEchoImpl : Echo<Int>
