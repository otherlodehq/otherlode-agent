package dev.otherlode.config

/**
 * One annotation type the adopter named in the `callbackAnnotations` option.
 *
 * [written] is the spelling the adopter used, kept to name the entry in a log line. [dotted] is the
 * same name with every `$` turned into `.`, so a nested type written either way compares equal.
 */
data class CallbackAnnotationName(
    val written: String,
    val dotted: String,
)
