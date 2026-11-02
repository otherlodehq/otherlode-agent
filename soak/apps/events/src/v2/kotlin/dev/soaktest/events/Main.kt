package dev.soaktest.events

fun main() {
    Class.forName("org.apache.commons.codec.binary.Hex", false, ClassLoader.getSystemClassLoader())
    startEvents { store -> statsRoute(store) }
}
