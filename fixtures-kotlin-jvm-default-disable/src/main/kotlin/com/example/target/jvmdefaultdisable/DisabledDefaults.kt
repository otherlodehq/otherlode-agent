package com.example.target.jvmdefaultdisable

interface DisabledDefaultInterface {
    fun withBranch(x: Int): Int {
        if (x > 3) return 1
        return 2
    }

    fun withBody(): Int = 42

    val label: String
        get() = "disabled"

    fun callsPrivate(x: Int): Int = priv(x)

    private fun priv(x: Int): Int = x + 1
}

class DisabledDefaultInterfaceImpl : DisabledDefaultInterface

interface DisabledRunnableInterface : Runnable {
    override fun run() {}

    fun plain(): Int = 1
}

interface DisabledFunctionInterface : (String) -> Unit {
    override fun invoke(p1: String) {}
}
