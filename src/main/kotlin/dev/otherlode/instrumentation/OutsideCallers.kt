package dev.otherlode.instrumentation

import dev.otherlode.export.OutsideCaller
import dev.otherlode.export.OutsideCallerKind

/** Decides which [OutsideCaller], if any, a METHOD probe carries. */
internal object OutsideCallers {
    /**
     * The one outside caller of a method. The method's own callback annotation wins, since it says
     * most about who calls the method, then one it inherits from a method it overrides, then an
     * out-of-scope override. [annotationType] is the callback annotation as written on the method or
     * one of its parameters, [inheritedAnnotationType] the one as written on the supertype method,
     * and [overriddenType] the out-of-scope type whose method it overrides, all dotted. Any may be
     * null.
     */
    fun of(
        overriddenType: String?,
        annotationType: String?,
        inheritedAnnotationType: String? = null,
    ): OutsideCaller? =
        when {
            annotationType != null -> OutsideCaller(OutsideCallerKind.CALLBACK_ANNOTATION, annotationType.interned())
            inheritedAnnotationType != null -> OutsideCaller(OutsideCallerKind.CALLBACK_ANNOTATION, inheritedAnnotationType.interned())
            overriddenType != null -> OutsideCaller(OutsideCallerKind.OVERRIDES_METHOD, overriddenType.interned())
            else -> null
        }
}
