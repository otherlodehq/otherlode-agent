package dev.otherlode.config

/**
 * One annotation type the adopter named in the `callbackAnnotations` option.
 *
 * [written] is the spelling the adopter used. [dotted] is the same name with every `$` turned into
 * `.`.
 */
data class CallbackAnnotationName(
    val written: String,
    val dotted: String,
)
