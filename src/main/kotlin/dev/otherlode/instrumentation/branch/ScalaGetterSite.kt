package dev.otherlode.instrumentation.branch

/**
 * A Scala default getter, the compiler-generated method `f$default$N` that returns the default
 * value of one optional parameter of the target `f`.
 *
 * [parameterIndex] is `N - 1`, the target's zero-based JVM parameter index, which counts a Scala
 * extension receiver. [parameterName] is read from the target's `LocalVariableTable`, or is empty
 * when the target has no debug info. [line] is the getter's own first line, or -1 when the getter
 * has no line-number table.
 *
 * [targetClassName] is the dotted binary name of the class holding the target. It is set only for
 * a constructor default getter on a companion module class, and is null otherwise.
 *
 * [overridable] is false for a static, private or final target, a constructor, or a target on a
 * final class.
 */
data class ScalaGetterSite(
    val getterName: String,
    val getterDescriptor: String,
    val targetName: String,
    val targetDescriptor: String,
    val parameterIndex: Int,
    val parameterName: String,
    val overridable: Boolean,
    val targetClassName: String? = null,
    val line: Int = -1,
)
