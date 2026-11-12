package com.example.target.kotlinc

interface Greeter {
    fun name(): String

    fun greet(): String = "hi " + name()

    fun tag(s: String?): String = "" + s

    val label: String
        get() = "label"
}
