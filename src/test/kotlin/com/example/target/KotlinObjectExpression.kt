package com.example.target.outsidecaller

class KotlinObjectExpression {
    fun make(): Runnable =
        object : Runnable {
            override fun run() {}
        }
}
