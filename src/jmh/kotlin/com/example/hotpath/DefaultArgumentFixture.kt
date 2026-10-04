package com.example.hotpath

import dev.otherlode.benchmark.HotPathShape

/** Calls a function with three optional parameters, once omitting all three and once omitting two. */
class DefaultArgumentFixture : HotPathShape {
    /** The target of the `$default` calls. */
    fun scale(
        a: Int,
        b: Int = 2,
        c: Int = 3,
        d: Int = 4,
    ): Int = a * b + c - d

    override fun call(x: Int): Int = scale(x) + scale(x, 5)
}
