package com.example.target.jvmdefaultdisable

@JvmInline
value class DisabledId(
    val v: Int,
)

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

interface DisabledSuspendOverride : DisabledSuspend {
    override suspend fun run() = super.run()
}

@JvmInline
value class DisabledLabel(
    val s: String,
)

interface DisabledMid<T : CharSequence> {
    fun f(t: T): Int = t.length
}

interface DisabledMidApi : DisabledMid<String>

@JvmInline
value class DisabledMidValue(
    val v: Int,
) : DisabledMid<String>

interface DisabledHolds<T> {
    fun get(): T? = null

    fun put(t: T): T = t

    val item: T? get() = null
}

interface DisabledTakes<T> {
    fun take(t: T?): Int = if (t == null) 0 else 1
}

interface DisabledLabelHoldsApi : DisabledHolds<DisabledLabel>

interface DisabledLabelTakesApi : DisabledTakes<DisabledLabel>

@JvmInline
value class DisabledLabelHoldsValue(
    val v: Int,
) : DisabledHolds<DisabledLabel>

@JvmInline
value class DisabledLabelTakesValue(
    val v: Int,
) : DisabledTakes<DisabledLabel>

interface DisabledMidOverride : DisabledMid<String> {
    override fun f(t: String): Int = super.f(t)
}

interface DisabledLabelHoldsOverride : DisabledHolds<DisabledLabel> {
    override fun get(): DisabledLabel? = super.get()
}
