package com.example.target.mixed

class MixedIdStoreImpl : MixedIdStore

class MixedStringStoreImpl : MixedStore<String> {
    override fun plain(item: String): Int = super.plain(item) + 1
}

class MixedSuperStoreImpl : MixedStore<Any?> {
    override fun plain(item: Any?): Int = super.plain(item)
}
