package com.example.target

import kotlin.jvm.JvmSerializableLambda

class BodyKindTarget {
    val base = 10
    var counter = 0

    fun twice(value: Int): Int = value * 2

    fun functionReference(): (Int) -> Int = ::twice

    fun adaptedFunctionReference(): (Int) -> Unit = ::twice

    fun boundPropertyReference(): () -> Int = this::base

    fun unboundPropertyReference(): (BodyKindTarget) -> Int = BodyKindTarget::base

    fun mutablePropertyReference(): () -> Int = this::counter

    fun localClass(): Runnable {
        class Local : Runnable {
            override fun run() {
                twice(1)
            }
        }
        return Local()
    }

    fun serializableLambda(): () -> Int = @JvmSerializableLambda { twice(2) }

    fun suspendLambda(): suspend () -> Int = { twice(3) }

    fun restrictedSuspendLambda(): Sequence<Int> = sequence { yield(twice(4)) }
}

fun interface BodyKindIntOp {
    fun apply(value: Int): Int
}

fun funInterfaceConstructorReference(): ((Int) -> Int) -> BodyKindIntOp = ::BodyKindIntOp
