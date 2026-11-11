package dev.otherlode.instrumentation

internal fun String.interned(): String = intern()

internal fun String?.internedOrNull(): String? = this?.intern()

internal fun List<String>.internedAll(): List<String> =
    when (size) {
        0 -> emptyList()
        1 -> listOf(this[0].intern())
        else -> ArrayList<String>(size).also { copy -> for (name in this) copy.add(name.intern()) }
    }

internal fun <T> List<T>.rightSized(): List<T> = toList()
