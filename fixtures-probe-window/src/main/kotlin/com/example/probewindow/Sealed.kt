package com.example.probewindow

sealed class Result {
    companion object {
        val EMPTY: Result = Success(0)
    }
}

class Success(
    val value: Int,
) : Result() {
    fun describe(): String = if (value > 0) "positive" else "zero"
}

sealed interface Token {
    fun text(): String = "token"

    companion object {
        val EOF: Token = Eof()
    }
}

class Eof : Token

/** Constructs the subtype of a sealed class or interface before its supertype's companion has run. */
fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "token" -> println("token " + Eof().text())
        else -> println("result " + Success(1).describe())
    }
}
