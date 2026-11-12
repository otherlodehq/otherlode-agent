package com.example.target

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

class FunctionReferenceTarget {
    private fun secret(): Int = 1

    fun viaReference(): Int {
        val f: () -> Int = ::secret
        return f()
    }
}

class SuspendLambdaTarget {
    private fun helper(): Int = 2

    fun runIt(block: suspend () -> Unit) = block.startCoroutine(Continuation(EmptyCoroutineContext) { })

    fun usesSuspend() = runIt { helper() }
}

class ObjectExpressionTarget {
    private fun helper(): Int = 3

    fun makeHandler(): Runnable =
        object : Runnable {
            override fun run() {
                helper()
            }
        }
}

class NamedNestedTarget {
    class Nested {
        fun method(): Int = 4
    }
}

fun makesNamedNested(): NamedNestedTarget.Nested = NamedNestedTarget.Nested()

object NamedObjectTarget {
    fun method(): Int = 5
}

fun readsNamedObjectTarget(): NamedObjectTarget = NamedObjectTarget
