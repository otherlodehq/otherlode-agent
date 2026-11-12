package com.example.target

fun callsMultifileGreeting(): String = multifileGreeting("x")

fun callsMultifileFarewellWithDefault(): String = multifileFarewell("x")

fun readsMultifileSuffix(): String = multifileSuffix

fun callsAnotherFile(): Int = callsIntoOtherFacade()

class KindClass {
    fun f(): Int = 1
}

object KindObject {
    fun g(): Int = 2
}
