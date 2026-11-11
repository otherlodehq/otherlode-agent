package dev.otherlode.instrumentation.branch

import dev.otherlode.export.BodyKind

internal object BodyKindRule {
    data class OwnInnerClassEntry(
        val innerName: String?,
    )

    data class Classification(
        val kind: BodyKind,
        val sourceName: String? = null,
    ) {
        companion object {
            val NONE = Classification(BodyKind.NONE)
        }
    }

    private val lambdaBases =
        setOf(
            "jvm/internal/Lambda",
            "coroutines/jvm/internal/SuspendLambda",
            "coroutines/jvm/internal/RestrictedSuspendLambda",
        )

    fun classify(
        hasEnclosingMethod: Boolean,
        superInternalName: String?,
        ownInnerClassEntry: OwnInnerClassEntry?,
        hasKotlinMetadata: Boolean,
    ): Classification {
        if (!hasEnclosingMethod) return Classification.NONE
        if (runtimeBaseName(superInternalName) in lambdaBases) return Classification(BodyKind.LAMBDA_CLASS)
        val innerName = ownInnerClassEntry?.innerName
        return when {
            innerName != null -> Classification(BodyKind.LOCAL_CLASS, innerName)
            ownInnerClassEntry != null && hasKotlinMetadata -> Classification(BodyKind.OBJECT_EXPRESSION)
            else -> Classification(BodyKind.ANONYMOUS_CLASS)
        }
    }

    private fun runtimeBaseName(internalName: String?): String? {
        if (internalName == null) return null
        val slash = internalName.indexOf('/')
        if (slash <= 0) return null
        return internalName.substring(slash + 1)
    }
}
