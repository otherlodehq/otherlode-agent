package com.example.hotpath

import dev.otherlode.benchmark.HotPathShape

/** A loop over a `when` on an int, then an `if`, so it gets probes on its jumps and on each switch case. */
class BranchyFixture : HotPathShape {
    override fun call(x: Int): Int {
        var total = 0
        for (i in 0 until ROUNDS) {
            total +=
                when ((x + i) and MASK) {
                    0 -> 3
                    1 -> 5
                    2 -> 7
                    3 -> 11
                    else -> 1
                }
        }
        if (total > LIMIT) total -= 1
        return total
    }
}

private const val ROUNDS = 8
private const val MASK = 7
private const val LIMIT = 20
