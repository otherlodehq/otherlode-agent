package com.example.target.keypairs

import com.example.target.Tint

class SwitchLabelsKotlinV1 {
    fun enumWhen(tint: Tint): Int =
        when (tint) {
            Tint.RED -> 1
            Tint.BLUE -> 3
            else -> 0
        }
}

// Adding a case first renumbers every value in kotlinc's `$WhenMappings` array.
class SwitchLabelsKotlinV2 {
    fun enumWhen(tint: Tint): Int =
        when (tint) {
            Tint.GREEN -> 2
            Tint.RED -> 1
            Tint.BLUE -> 3
            else -> 0
        }
}
