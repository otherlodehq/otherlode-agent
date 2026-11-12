package com.example.target

import com.example.other.OtherTarget

enum class Suit { HEARTS, SPADES }

object Config {
    @JvmField
    var flag: Boolean = true
}

object SelfStaticUser {
    @JvmField
    var counter: Int = 0

    fun touchOwnCounter(): Int {
        counter = 5
        return counter
    }
}

class FinalMethodTarget {
    fun method(): Int = 5
}

class StaticUseTarget {
    fun readEnumConstant(): Suit = Suit.HEARTS

    fun readAndWriteConfigFlag(): Boolean {
        Config.flag = false
        return Config.flag
    }

    fun readJavaStaticField(): Int = OtherTarget.counter

    fun writeJavaStaticField() {
        OtherTarget.counter = 5
    }

    fun readSystemOut(): java.io.PrintStream = System.out

    fun touchesOtherFieldAndConstructs(): Int {
        val other = OtherTarget()
        other.value = 3
        return other.value
    }

    fun callsFinalMethod(target: FinalMethodTarget): Int = target.method()
}
