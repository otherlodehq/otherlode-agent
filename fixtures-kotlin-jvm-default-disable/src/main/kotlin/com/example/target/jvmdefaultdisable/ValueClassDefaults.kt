package com.example.target.jvmdefaultdisable

@JvmInline
value class DisabledId(
    val v: Int,
)

/** Defaults that take and return the type parameter, fixed by the sub-interfaces below at a value class and at `Unit`. */
interface DisabledStore<T> {
    fun put(t: T): T = t

    fun get(): T? = null
}

interface DisabledIdStore : DisabledStore<DisabledId>

interface DisabledUnitStore : DisabledStore<Unit>

interface DisabledSuspend {
    suspend fun run() {
        println("x")
    }
}

interface DisabledSuspendSub : DisabledSuspend

/** Overrides a suspend default only to call `super`: the same tail as a sub-interface's forwarder, with no receiver cast. */
interface DisabledSuspendOverride : DisabledSuspend {
    override suspend fun run() = super.run()
}
