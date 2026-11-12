package com.example.target

import com.example.library.Lib

class ReferenceAttributionTarget {
    private fun secret(): Lib.Secret? = null

    inner class Inner {
        fun callSecret(): Any? = secret()
    }

    fun withDefault(value: Any? = Lib.DefaultValue.make()): Any? = value

    fun callsDefault(): Any? = withDefault()

    fun callsHelper(): Any? = helper()

    private fun helper(): Any? = Lib.Helper()

    fun makesLambda(): () -> Unit = { Lib.LambdaBody.run() }

    fun makesObject(): Runnable =
        object : Runnable {
            override fun run() {
                Lib.BodyRef.run()
            }
        }
}

class ReferenceDefaultCaller {
    fun call(target: ReferenceAttributionTarget): Any? = target.withDefault()
}
