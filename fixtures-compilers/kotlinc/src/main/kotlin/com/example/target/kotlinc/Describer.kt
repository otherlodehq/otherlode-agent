package com.example.target.kotlinc

interface Describer {
    fun id(): String

    fun describe(text: String): String = text + id()

    fun maybe(text: String?): String = "" + text + id()

    fun plain(): String = "plain " + id()

    val title: String
        get() = "title " + id()
}

class DescriberImpl : Describer {
    override fun id(): String = "impl"
}

class HandForwarder : Describer {
    override fun id(): String = "hand"

    fun forwardOnly(s: String): String = helper(s)

    private fun helper(s: String): String = s + "!"
}

class DescriberCaller {
    fun call(impl: DescriberImpl): String = impl.plain() + impl.describe("x")
}

interface SubDescriber : Describer

class SubDescriberImpl : SubDescriber {
    override fun id(): String = "sub"
}

interface Store<T> {
    fun load(): T? = null

    fun save(item: T): String = "saved " + item

    fun count(n: Int): Long = n.toLong()
}

class StringStore : Store<String>

interface Tally<T> {
    fun add(item: T): String = "added " + item
}

class IntTally : Tally<Int>

open class OpenDescriber : Describer {
    override fun id(): String = "open"

    fun own(): String = plain()
}

class PlainDescriber : OpenDescriber() {
    fun inherited(): String = plain()
}

class PlainDescriberCaller {
    fun call(describer: PlainDescriber): String = describer.plain()
}

class LoudDescriber : OpenDescriber() {
    override fun plain(): String = "PLAIN"
}

class OpenDescriberCaller {
    fun call(describer: OpenDescriber): String = describer.plain()
}

class SuperDescriber : Describer {
    override fun id(): String = "super"

    override fun plain(): String = super.plain()
}

class InterfaceCaller {
    fun call(describer: Describer): String = describer.plain()
}

open class MidDescriber : OpenDescriber()

class DeepDescriber : MidDescriber()

class DeepDescriberCaller {
    fun call(describer: DeepDescriber): String = describer.plain()

    fun callOrdinary(describer: DeepDescriber): String = describer.id()
}

class InsideDescriber : com.example.outside.OutsideDescriber()

class InsideDescriberCaller {
    fun call(describer: InsideDescriber): String = describer.plain()
}

class SuperCallingDescriber : OpenDescriber() {
    fun viaSuper(): String = super.plain()
}

interface StringStoreApi : Store<String>

interface IntTallyApi : Tally<Int>

interface Echo<T> {
    fun echo(item: T): T = item

    suspend fun later(item: T): String = "later " + item
}

interface IntEcho : Echo<Int>

class IntEchoImpl : Echo<Int>
