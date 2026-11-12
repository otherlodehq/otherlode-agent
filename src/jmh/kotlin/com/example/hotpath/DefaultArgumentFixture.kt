package com.example.hotpath

import dev.otherlode.benchmark.HotPathShape

class DefaultArgumentFixture : HotPathShape {
    fun scale(
        a: Int,
        b: Int = 2,
        c: Int = 3,
        d: Int = 4,
    ): Int = a * b + c - d

    override fun call(x: Int): Int = scale(x) + scale(x, 5)
}
