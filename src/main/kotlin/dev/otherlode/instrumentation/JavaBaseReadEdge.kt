package dev.otherlode.instrumentation

import java.lang.instrument.Instrumentation

internal fun addJavaBaseReadEdge(
    instrumentation: Instrumentation,
    seam: Class<*>,
) {
    val javaBase = Any::class.java.module
    instrumentation.redefineModule(javaBase, setOf(seam.module), emptyMap(), emptyMap(), emptySet(), emptyMap())
}
