@file:Suppress("ktlint:standard:statement-wrapping")

package com.example.target.kotlinc

interface Shelf<T> {
    fun put(t: T): Any = t as Any

    fun size(): Int = 0
}

interface Rack {
    fun put(t: Int): Any = "rack"
}

open class OverloadShelf : Shelf<Int?> {
    open fun put(t: Int): Any = super.put(t)
}

open class LoneOverload : Shelf<Int?> {
    override fun put(t: Int?): Any = "own"

    open fun put(t: Int): Any = super.put(t)
}

class BothShelf :
    Shelf<Int>,
    Rack {
    override fun put(t: Int): Any = super<Shelf>.put(t)
}

@Suppress("unused")
@Deprecated("kept")
open class
HeaderShelf : Shelf<String> {
    fun extra(): Int = 1
}

class OneLineShelf : Shelf<Int> { override fun put(t: Int): Any = super.put(t) }
