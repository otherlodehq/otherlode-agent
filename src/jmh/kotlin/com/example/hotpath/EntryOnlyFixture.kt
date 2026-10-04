package com.example.hotpath

import dev.otherlode.benchmark.HotPathShape

/** A method of a few bytes, so the only probe it gets is the one at its entry. */
class EntryOnlyFixture : HotPathShape {
    override fun call(x: Int): Int = x + 1
}
