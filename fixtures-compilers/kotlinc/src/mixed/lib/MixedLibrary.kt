package com.example.target.mixed

@JvmInline
value class MixedId(
    val value: Int,
)

/**
 * Compiled with `-jvm-default=disable`. [plain] and [keyed] keep their bodies in
 * `MixedStore$DefaultImpls`.
 */
interface MixedStore<T> {
    fun plain(item: T): Int = 1

    fun keyed(
        id: MixedId,
        item: T,
    ): Int = 2
}

interface MixedIdStore : MixedStore<MixedId>
