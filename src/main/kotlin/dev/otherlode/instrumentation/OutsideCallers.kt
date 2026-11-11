package dev.otherlode.instrumentation

import dev.otherlode.export.OutsideCaller
import dev.otherlode.export.OutsideCallerKind

internal object OutsideCallers {
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
