package dev.otherlode.instrumentation.branch

/**
 * One resolved Kotlin `$default` method: a compiler-generated method that fills in the default
 * value for every optional parameter a caller omitted, then calls the real target.
 *
 * [optionalBits] has one set bit per optional parameter, at the parameter's zero-based value
 * index (receivers not counted). [maskParameterIndex] is the position of the first mask `int`
 * among [defaultName]'s own declared parameters.
 *
 * [overridable] is false for a constructor, a static or private or final method, or a method on
 * a final class.
 *
 * [parameterNames] maps a set bit in [optionalBits] to the target's parameter name, or to an
 * empty string when the target has no debug info.
 *
 * [defaultLines] maps a set bit in [optionalBits] to the line in force where its default value
 * is filled in, or to -1 when no line number precedes that point.
 *
 * [higherMaskTested] is true when [defaultName]'s body tests a mask `int` beyond the first. Only
 * the first mask `int` is bound, so a caller finding this true should log that any bit past the
 * 32nd value parameter goes uncounted.
 */
data class DefaultSite(
    val defaultName: String,
    val defaultDescriptor: String,
    val targetName: String,
    val targetDescriptor: String,
    val optionalBits: Int,
    val higherMaskTested: Boolean,
    val overridable: Boolean,
    val maskParameterIndex: Int,
    val parameterNames: Map<Int, String> = emptyMap(),
    val defaultLines: Map<Int, Int> = emptyMap(),
)
