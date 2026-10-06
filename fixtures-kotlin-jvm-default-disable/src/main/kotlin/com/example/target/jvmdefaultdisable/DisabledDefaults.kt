package com.example.target.jvmdefaultdisable

/**
 * Compiled with `-jvm-default=disable`, so each default body below lives in
 * `DisabledDefaultInterface$DefaultImpls` and the interface methods are abstract. Mirrors
 * `com.example.target.GeneratedInterface`, which compiles under the root build's default mode
 * and so gets a forwarding `$DefaultImpls` instead.
 *
 * [callsPrivate] is the near miss: its body loads its arguments, makes one `invokestatic` and
 * returns, but the call's owner is `$DefaultImpls` itself, where [priv]'s body lives, not the
 * interface.
 */
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

/**
 * Extends a JDK interface and gives its method a default body. Under `-jvm-default=disable` that
 * body is `run(LDisabledRunnableInterface;)V` in the interface's `$DefaultImpls`. [plain] overrides
 * nothing.
 */
interface DisabledRunnableInterface : Runnable {
    override fun run() {}

    fun plain(): Int = 1
}

/**
 * Gives a generic method of a Kotlin function type a default body. Under `-jvm-default=disable`
 * the body is `invoke(LDisabledFunctionInterface;Ljava/lang/String;)V` in the `$DefaultImpls`
 * class, and the interface holds no bridge for `Function1.invoke(Ljava/lang/Object;)Ljava/lang/Object;`.
 */
interface DisabledFunctionInterface : (String) -> Unit {
    override fun invoke(p1: String) {}
}
