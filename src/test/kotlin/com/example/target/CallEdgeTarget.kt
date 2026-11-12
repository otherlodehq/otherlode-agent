package com.example.target

import com.example.other.OtherTarget

fun staticHelper(): Int = 42

interface Classifier {
    fun classify(value: Int): Int
}

class ClassifierImpl : Classifier {
    override fun classify(value: Int): Int = value
}

fun applyOp(
    op: (Int) -> Int,
    value: Int,
): Int = op(value)

class CallEdgeTarget {
    private fun privateHelper(): Int = 7

    fun callsPrivateMethod(): Int = privateHelper()

    fun callsOtherClass(): Int {
        val other = OtherTarget()
        other.doSomething()
        return 1
    }

    fun callsStaticFunction(): Int = staticHelper()

    fun callsJdkMethod(): Long = System.currentTimeMillis()

    fun callsKotlinStdlib(): String = listOf(1, 2, 3).joinToString()

    fun callsInterfaceMethod(c: Classifier): Int = c.classify(1)

    fun callsSelfRecursively(n: Int): Int = if (n <= 0) 0 else callsSelfRecursively(n - 1)

    fun callsWithDefaultArgument(x: DefaultArgumentTarget): Int = x.f(1)

    fun callsLambda(): Int = applyOp({ it + 1 }, 5)
}

class CycleTarget {
    fun real(): Int = a()

    fun a(): Int = b()

    fun b(): Int = a()
}

abstract class TemplateTarget {
    fun run(): Int = step()

    abstract fun step(): Int
}

open class BaseTarget {
    fun inherited(): Int = 1
}

class SubTarget : BaseTarget() {
    fun go(): Int = inherited()
}
