package dev.otherlode.instrumentation

import dev.otherlode.export.KotlinKind
import net.bytebuddy.description.method.MethodDescription
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.matcher.ElementMatcher
import net.bytebuddy.matcher.ElementMatchers.any
import net.bytebuddy.matcher.ElementMatchers.isAbstract
import net.bytebuddy.matcher.ElementMatchers.isBridge
import net.bytebuddy.matcher.ElementMatchers.isNative
import net.bytebuddy.matcher.ElementMatchers.isTypeInitializer
import net.bytebuddy.matcher.ElementMatchers.not

/** Decides which types and methods the agent instruments. */
object TypeMatchPolicy {
    /** The agent's own classes are never instrumented, whatever `includePackages` says. */
    const val AGENT_PACKAGE_PREFIX = "dev.otherlode."
    private const val AGENT_PACKAGE_INTERNAL_PREFIX = "dev/otherlode/"

    /**
     * Whether the dotted [className] is matched by [includePackages] and not by [excludePackages].
     *
     * An empty include list matches nothing. Exclusion wins over inclusion, and the agent's own
     * package is never included.
     */
    fun isIncluded(
        className: String,
        includePackages: List<String>,
        excludePackages: List<String>,
    ): Boolean {
        if (className.startsWith(AGENT_PACKAGE_PREFIX)) return false
        if (includePackages.none { isUnderPrefix(className, it) }) return false
        return excludePackages.none { isUnderPrefix(className, it) }
    }

    /** [isIncluded] for an internal name (`com/acme/Foo`). The prefixes stay dotted. */
    fun isIncludedInternal(
        internalName: String,
        includePackages: List<String>,
        excludePackages: List<String>,
    ): Boolean {
        if (internalName.startsWith(AGENT_PACKAGE_INTERNAL_PREFIX)) return false
        if (includePackages.none { isUnderInternalPrefix(internalName, it) }) return false
        return excludePackages.none { isUnderInternalPrefix(internalName, it) }
    }

    /** [isUnderPrefix] for an internal name against a dotted [prefix]. */
    fun isUnderInternalPrefix(
        internalName: String,
        prefix: String,
    ): Boolean {
        if (internalName.length < prefix.length) return false
        for (index in prefix.indices) {
            val character = internalName[index]
            if (prefix[index] != (if (character == '/') '.' else character)) return false
        }
        if (internalName.length == prefix.length) return true
        val boundary = internalName[prefix.length]
        return boundary == '/' || boundary == '.' || boundary == '$'
    }

    /**
     * Whether [className] is [prefix] or under it, matching on a package or class boundary only:
     * `com.acme` matches `com.acme.Foo` but not `com.acmeinternal.Foo`.
     */
    fun isUnderPrefix(
        className: String,
        prefix: String,
    ): Boolean {
        if (!className.startsWith(prefix)) return false
        if (className.length == prefix.length) return true
        val boundary = className[prefix.length]
        return boundary == '.' || boundary == '$'
    }

    /** The types the agent instruments: in scope by [isIncluded] and not turned away by [isTurnedAwayByShape]. */
    fun typeNameMatcher(
        includePackages: List<String>,
        excludePackages: List<String>,
    ): ElementMatcher.Junction<TypeDescription> =
        any<TypeDescription>().and { typeDescription: TypeDescription ->
            isIncluded(typeDescription.name, includePackages, excludePackages) &&
                !isTurnedAwayByShape(
                    typeDescription.name,
                    typeDescription.isSynthetic,
                    { superClassNameOf(typeDescription) },
                    { kotlinKindOf(typeDescription) },
                )
        }

    /**
     * Whether [typeNameMatcher] turns a class away for what the class states about itself: it is
     * synthetic, it is runtime generated ([isRuntimeGenerated]), or it is a suspend function's own
     * continuation ([isContinuationSuperclass]).
     *
     * A synthetic class of kind [KotlinKind.MULTIFILE_CLASS_PART] is not turned away.
     * [superClassName] and [kotlinKind] are read only when needed.
     */
    fun isTurnedAwayByShape(
        className: String,
        isSynthetic: Boolean,
        superClassName: () -> String?,
        kotlinKind: () -> KotlinKind,
    ): Boolean =
        (isSynthetic && kotlinKind() != KotlinKind.MULTIFILE_CLASS_PART) ||
            isRuntimeGenerated(className) ||
            isContinuationSuperclass(superClassName())

    // A literal starting with `kotlin.` would be rewritten by shadowJar.
    private val KOTLIN_METADATA_NAME_SHAPE = Regex("^[^.]+\\.Metadata$")

    /**
     * The kind in the `k` element of [typeDescription]'s `kotlin.Metadata`, by [KotlinKind.ofMetadataKind], or
     * [KotlinKind.NONE] when the class carries no such annotation. A `k` that cannot be read gives
     * [KotlinKind.KOTLIN_CLASS].
     */
    fun kotlinKindOf(typeDescription: TypeDescription): KotlinKind {
        val metadata =
            try {
                typeDescription.declaredAnnotations.firstOrNull { KOTLIN_METADATA_NAME_SHAPE.matches(it.annotationType.name) }
            } catch (_: Exception) {
                null
            } ?: return KotlinKind.NONE
        val k =
            try {
                metadata.getValue("k").resolve() as? Int
            } catch (_: Exception) {
                null
            }
        return KotlinKind.ofMetadataKind(k)
    }

    private val RUNTIME_GENERATED_NAME_MARKERS = listOf("\$\$SpringCGLIB\$\$", "BySpringCGLIB\$\$", "_\$\$_jvst")

    private val RANDOM_TAILED_PARTS = setOf("ByteBuddy", "MockitoMock")

    private val RANDOM_TAIL = Regex("[0-9A-Za-z]{8}|[0-9A-Za-z]{15}")

    private val WORD_SHAPED = Regex("[A-Z]?[a-z]+")

    private val JDK_PROXY_SIMPLE_NAME = Regex("\\\$Proxy\\d+")

    private val HIBERNATE_GENERATED_SUFFIXES = setOf("HibernateProxy", "HibernateBasicProxy", "HibernateInstantiator")

    private const val HIBERNATE_ACCESS_OPTIMIZER = "HibernateAccessOptimizer"

    private fun isHibernateGenerated(className: String): Boolean =
        "\$Hibernate" in className &&
            className.substringAfterLast('.').split('$').drop(1).any { part ->
                part in HIBERNATE_GENERATED_SUFFIXES || isHibernateAccessOptimizer(part)
            }

    private fun isHibernateAccessOptimizer(part: String): Boolean {
        if (!part.startsWith(HIBERNATE_ACCESS_OPTIMIZER)) return false
        val encoded = part.removePrefix(HIBERNATE_ACCESS_OPTIMIZER).removePrefix("Bridge")
        return encoded.isEmpty() || encoded[0] in '0'..'9' || encoded[0] in 'a'..'f'
    }

    /** Whether [className] names a class a framework generated at runtime. */
    fun isRuntimeGenerated(className: String): Boolean = isRuntimeGenerated(className, byteBuddyNaming)

    internal fun isRuntimeGenerated(
        className: String,
        byteBuddyNaming: String?,
    ): Boolean =
        RUNTIME_GENERATED_NAME_MARKERS.any { it in className } ||
            isHibernateGenerated(className) ||
            isRandomTailed(className) ||
            (isUntailedByteBuddyNaming(byteBuddyNaming) && endsInByteBuddyPart(className)) ||
            JDK_PROXY_SIMPLE_NAME.matches(className.substringAfterLast('.'))

    // Built at runtime: the shaded jar relocates `net.bytebuddy` and would rewrite a literal.
    internal val BYTE_BUDDY_NAMING_PROPERTY = listOf("net", "bytebuddy", "naming").joinToString(".")

    // Built at runtime for the same reason as BYTE_BUDDY_NAMING_PROPERTY.
    internal val BYTE_BUDDY_PACKAGE_PREFIX = listOf("net", "bytebuddy", "").joinToString(".")

    private val IGNORED_NAME_PREFIXES = listOf(BYTE_BUDDY_PACKAGE_PREFIX, "sun.reflect.", "jdk.internal.reflect.")

    /** Whether [className] is under ByteBuddy's package or the reflection internals. */
    fun isIgnoredName(className: String): Boolean = IGNORED_NAME_PREFIXES.any { className.startsWith(it) }

    @Volatile
    internal var byteBuddyNaming: String? =
        try {
            System.getProperty(BYTE_BUDDY_NAMING_PROPERTY)
        } catch (_: SecurityException) {
            null
        }

    private fun isUntailedByteBuddyNaming(byteBuddyNaming: String?): Boolean =
        byteBuddyNaming.equals("fixed", ignoreCase = true) || byteBuddyNaming.equals("caller", ignoreCase = true)

    private fun endsInByteBuddyPart(className: String): Boolean {
        val parts = className.substringAfterLast('.').split('$')
        return parts.size > 1 && parts.last() == "ByteBuddy"
    }

    private fun isRandomTailed(className: String): Boolean =
        className
            .substringAfterLast('.')
            .split('$')
            .drop(1)
            .zipWithNext()
            .any { (part, next) -> part in RANDOM_TAILED_PARTS && RANDOM_TAIL.matches(next) && !WORD_SHAPED.matches(next) }

    // A literal starting with `kotlin.` would be rewritten by shadowJar.
    internal val CONTINUATION_SUPERCLASS_SUFFIXES =
        listOf(
            ".coroutines.jvm.internal.ContinuationImpl",
            ".coroutines.jvm.internal.RestrictedContinuationImpl",
        )

    /**
     * Whether [superClassName], dotted, is the direct superclass of a suspend function's own
     * continuation class. A class extending `SuspendLambda` is not.
     */
    fun isContinuationSuperclass(superClassName: String?): Boolean =
        superClassName != null && CONTINUATION_SUPERCLASS_SUFFIXES.any { superClassName.endsWith(it) }

    private fun superClassNameOf(typeDescription: TypeDescription): String? =
        try {
            typeDescription.superClass?.asErasure()?.name
        } catch (_: Exception) {
            null
        }

    /**
     * Matches the methods that get a probe: not abstract, native, bridge or a type initializer, and
     * not synthetic unless a javac lambda body, or a scalac one when [isScalaClass] is true.
     * Hibernate enhancement methods ([isEnhancementMethod]) and the entry points of a suspend
     * lambda's class ([isSuspendLambdaEntry]) are left out.
     */
    fun methodMatcher(isScalaClass: Boolean): ElementMatcher.Junction<MethodDescription> =
        not(isAbstract<MethodDescription>())
            .and(not(isNative()))
            .and(not(isBridge()))
            .and(not(isTypeInitializer()))
            .and { method ->
                (!method.isSynthetic || isProbedLambdaBody(method.name, isScalaClass)) &&
                    !isEnhancementMethod(method.name) &&
                    !isSuspendLambdaEntry(method.name, method.isStatic) { superClassNameOf(method.declaringType.asErasure()) }
            }

    // A literal starting with `kotlin.` would be rewritten by shadowJar.
    internal val SUSPEND_LAMBDA_SUPERCLASS_SUFFIXES =
        listOf(
            ".coroutines.jvm.internal.SuspendLambda",
            ".coroutines.jvm.internal.RestrictedSuspendLambda",
        )

    /**
     * Whether a method named [name] is the instance `create` or `invoke` of a suspend lambda's
     * class, whose direct superclass, dotted, [superClassName] gives. [superClassName] is read only
     * for those two names.
     */
    fun isSuspendLambdaEntry(
        name: String,
        isStatic: Boolean,
        superClassName: () -> String?,
    ): Boolean {
        if (isStatic || (name != "create" && name != "invoke")) return false
        val superName = superClassName() ?: return false
        return SUSPEND_LAMBDA_SUPERCLASS_SUFFIXES.any { superName.endsWith(it) }
    }

    private const val ENHANCEMENT_METHOD_PREFIX = "\$\$_hibernate_"

    /** Whether a method named [name] was added by Hibernate's bytecode enhancement. */
    fun isEnhancementMethod(name: String): Boolean = name.startsWith(ENHANCEMENT_METHOD_PREFIX)

    /**
     * Whether a method named [name] that its owner's class file does not declare is one an enhancer
     * adds after the class file was written: Hibernate's ([isEnhancementMethod]) or Ebean's.
     */
    fun isUndeclaredEnhancementName(name: String): Boolean = isEnhancementMethod(name) || JpaEnhancement.isEbeanName(name)

    internal fun isProbedLambdaBody(
        name: String,
        isScalaClass: Boolean,
    ): Boolean = isJavacLambdaBodyName(name) || (isScalaClass && isScalacLambdaBodyName(name))

    /**
     * Whether [name] is a compiler-generated lambda body name: javac `lambda$<method>$N`, scalac 2
     * `$anonfun$<method>$N` (not the `$adapted` forwarder), scalac 3 `<owner>$$anonfun$N`, or kotlinc
     * `<method>$lambda$N`. The name alone does not prove a lambda body.
     */
    fun isLambdaBodyName(name: String): Boolean =
        isJavacLambdaBodyName(name) || isScalacLambdaBodyName(name) || KOTLINC_LAMBDA_BODY_NAME.matches(name)

    private fun isJavacLambdaBodyName(name: String): Boolean = name.startsWith("lambda\$")

    private fun isScalacLambdaBodyName(name: String): Boolean =
        (name.startsWith("\$anonfun\$") && !name.endsWith("\$adapted")) || SCALA3_METHOD_OWNED_LAMBDA_BODY_NAME.matches(name)

    /**
     * Whether [name] is scalac 3's name for the method a nested class calls to create a lambda lifted
     * into the top-level class: the body itself or its boxing bridge.
     */
    fun isScala3LiftedLambdaName(name: String): Boolean = SCALA3_LIFTED_LAMBDA_NAME.matches(name)

    private val SCALA3_LIFTED_LAMBDA_NAME = Regex("^.+\\\$\\\$(_\\\$)+.*\\\$anonfun\\\$(adapted\\\$)?\\d+$")

    private val SCALA3_METHOD_OWNED_LAMBDA_BODY_NAME = Regex("^.+\\\$\\\$anonfun\\\$\\d+$")

    private val KOTLINC_LAMBDA_BODY_NAME = Regex("^.+\\\$lambda\\\$\\d+(\\\$\\d+)*$")
}
