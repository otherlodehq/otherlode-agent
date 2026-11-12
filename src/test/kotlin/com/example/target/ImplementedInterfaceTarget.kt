package com.example.target

import com.sun.net.httpserver.HttpServer

fun interface IntStep {
    fun step(value: Int): Int
}

class ImplementedInterfaceTarget {
    fun register(server: HttpServer) {
        server.createContext("/implemented") { exchange -> exchange.close() }
    }

    fun startsThread(): Thread = Thread { touch() }

    fun funInterface(): Int = IntStep { it + 1 }.step(1)

    fun functionType(): Int {
        val f: (Int) -> Int = { it }
        return f(2)
    }

    fun objectExpression(): Runnable =
        object : Runnable {
            override fun run() {
                touch()
            }
        }

    fun touch(): Int = 1
}
