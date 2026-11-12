package com.example.hotpath

import dev.otherlode.benchmark.HotPathShape

class EntryOnlyFixture : HotPathShape {
    override fun call(x: Int): Int = x + 1
}
