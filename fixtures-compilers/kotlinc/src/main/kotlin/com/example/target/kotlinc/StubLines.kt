@file:Suppress("ktlint:standard:statement-wrapping")

package com.example.target.kotlinc

/** A generic interface whose default takes its type parameter, for the stub-line classes below. */
interface Shelf<T> {
    fun put(t: T): Any = t as Any

    fun size(): Int = 0
}

/** A second interface with a `put` of its own, so a class implementing both must override it. */
interface Rack {
    fun put(t: Int): Any = "rack"
}

/** Holds a stub and, beside it, an open overload the adopter wrote with a `super` call. */
open class OverloadShelf : Shelf<Int?> {
    open fun put(t: Int): Any = super.put(t)
}

/** Overrides the nullable default itself, so its open overload has no boxing stub beside it. */
open class LoneOverload : Shelf<Int?> {
    override fun put(t: Int?): Any = "own"

    open fun put(t: Int): Any = super.put(t)
}

/** Overrides a member both interfaces declare, with a body that only calls `super`. */
class BothShelf :
    Shelf<Int>,
    Rack {
    override fun put(t: Int): Any = super<Shelf>.put(t)
}

/** Its header spans lines and starts with an annotation, and its stubs sit beside an ordinary method. */
@Suppress("unused")
@Deprecated("kept")
open class
HeaderShelf : Shelf<String> {
    fun extra(): Int = 1
}

/** Written on one line, so an exact override cannot be told from the stub. */
class OneLineShelf : Shelf<Int> { override fun put(t: Int): Any = super.put(t) }
