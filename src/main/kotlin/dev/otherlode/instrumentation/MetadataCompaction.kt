package dev.otherlode.instrumentation

/**
 * Canonical instance of a name that repeats across classes and probes: a class name, a method name,
 * a descriptor or a generic signature. The JDK's string table holds its entries weakly, so a name
 * goes once nothing else holds it.
 */
internal fun String.interned(): String = intern()

/** [interned] for a value that may be absent. */
internal fun String?.internedOrNull(): String? = this?.intern()

/** Each element of this list interned, in an exactly sized list. */
internal fun List<String>.internedAll(): List<String> =
    when (size) {
        0 -> emptyList()
        1 -> listOf(this[0].intern())
        else -> ArrayList<String>(size).also { copy -> for (name in this) copy.add(name.intern()) }
    }

/**
 * A list holding exactly this one's elements and no spare capacity: the shared empty list for none,
 * a single-element list for one, an exact-size copy for more. Metadata held until delivery is
 * built with a mutable list that grows by doubling, and a retained one keeps the slack.
 */
internal fun <T> List<T>.rightSized(): List<T> = toList()
