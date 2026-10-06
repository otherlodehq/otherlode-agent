package com.example.target.outsidecaller

/** Makes a Kotlin object expression that implements a JDK interface. */
class KotlinObjectExpression {
    fun make(): Runnable =
        object : Runnable {
            override fun run() {}
        }
}
