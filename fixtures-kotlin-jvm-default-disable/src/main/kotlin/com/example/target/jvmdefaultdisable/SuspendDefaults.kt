package com.example.target.jvmdefaultdisable

import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

suspend fun pauseHere(): Int = suspendCoroutineUninterceptedOrReturn { 1 }

interface SuspendDefaultInterface {
    suspend fun run(x: Int): Int {
        val a = pauseHere()
        return if (x > a) 1 else 2
    }
}
