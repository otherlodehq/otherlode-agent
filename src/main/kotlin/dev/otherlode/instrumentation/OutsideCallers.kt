package dev.otherlode.instrumentation

import dev.otherlode.export.OutsideCaller
import dev.otherlode.export.OutsideCallerKind

/** Decides which [OutsideCaller], if any, a METHOD probe carries. */
internal object OutsideCallers {
    /**
     * The one outside caller of a method. A callback annotation wins over an override, since it
     * says more about who calls the method. [overriddenType] is the out-of-scope type whose method
     * the method overrides, dotted, and [annotationType] is the callback annotation as written on
     * the method. Either may be null.
     */
    fun of(
        overriddenType: String?,
        annotationType: String? = null,
    ): OutsideCaller? =
        when {
            annotationType != null -> OutsideCaller(OutsideCallerKind.CALLBACK_ANNOTATION, annotationType.interned())
            overriddenType != null -> OutsideCaller(OutsideCallerKind.OVERRIDES_METHOD, overriddenType.interned())
            else -> null
        }
}
