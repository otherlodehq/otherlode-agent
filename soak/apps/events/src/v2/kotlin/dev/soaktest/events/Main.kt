package dev.soaktest.events

fun main() {
    startEvents { store -> statsRoute(store) }
}
