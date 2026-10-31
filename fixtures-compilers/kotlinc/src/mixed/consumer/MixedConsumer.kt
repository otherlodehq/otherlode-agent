package com.example.target.mixed

/**
 * Compiled with `-jvm-default=all` or `all-compatibility` against the library. kotlinc writes
 * [keyed]'s stub with a mangled name for `MixedIdStore$DefaultImpls` that the library never wrote.
 */
class MixedIdStoreImpl : MixedIdStore

/** The adopter's override of a default, which calls an existing `$DefaultImpls` method. */
class MixedStringStoreImpl : MixedStore<String> {
    override fun plain(item: String): Int = super.plain(item) + 1
}

/** The adopter's override that is only a `super` call, on a line after its class's declaration. */
class MixedSuperStoreImpl : MixedStore<Any?> {
    override fun plain(item: Any?): Int = super.plain(item)
}
