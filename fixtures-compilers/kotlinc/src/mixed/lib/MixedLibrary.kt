package com.example.target.mixed

@JvmInline
value class MixedId(
    val value: Int,
)

interface MixedStore<T> {
    fun plain(item: T): Int = 1

    fun keyed(
        id: MixedId,
        item: T,
    ): Int = 2
}

interface MixedIdStore : MixedStore<MixedId>
