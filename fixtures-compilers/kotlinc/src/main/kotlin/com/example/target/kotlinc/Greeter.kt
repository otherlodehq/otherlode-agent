package com.example.target.kotlinc

/**
 * An interface whose default methods take no parameters or only nullable ones, so each
 * `$DefaultImpls` forwarder starts with no null check. Nothing implements it: a class that did
 * would get a stub for each default under `-jvm-default=disable`.
 */
interface Greeter {
    fun name(): String

    fun greet(): String = "hi " + name()

    fun tag(s: String?): String = "" + s

    val label: String
        get() = "label"
}
