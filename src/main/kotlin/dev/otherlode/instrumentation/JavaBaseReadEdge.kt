package dev.otherlode.instrumentation

import java.lang.instrument.Instrumentation

/**
 * Lets `java.base` read the module of [seam], a class in the bootstrap jar, so inlined advice in a
 * `java.base` class can call it. The edge opens no package to any module.
 */
internal fun addJavaBaseReadEdge(
    instrumentation: Instrumentation,
    seam: Class<*>,
) {
    val javaBase = Any::class.java.module
    instrumentation.redefineModule(javaBase, setOf(seam.module), emptyMap(), emptyMap(), emptySet(), emptyMap())
}
