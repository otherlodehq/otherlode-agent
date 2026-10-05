package dev.otherlode.instrumentation

import net.bytebuddy.ByteBuddy
import net.bytebuddy.ClassFileVersion
import net.bytebuddy.agent.builder.AgentBuilder
import net.bytebuddy.asm.AsmVisitorWrapper
import net.bytebuddy.description.method.MethodDescription
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.ClassFileLocator
import net.bytebuddy.dynamic.DynamicType
import net.bytebuddy.dynamic.scaffold.MethodGraph
import net.bytebuddy.dynamic.scaffold.TypeValidation
import net.bytebuddy.dynamic.scaffold.inline.DecoratingDynamicTypeBuilder
import net.bytebuddy.dynamic.scaffold.inline.MethodNameTransformer
import net.bytebuddy.implementation.Implementation
import net.bytebuddy.implementation.attribute.AnnotationRetention
import net.bytebuddy.implementation.attribute.AnnotationValueFilter
import net.bytebuddy.implementation.attribute.TypeAttributeAppender
import net.bytebuddy.implementation.auxiliary.AuxiliaryType
import net.bytebuddy.matcher.ElementMatchers
import net.bytebuddy.matcher.LatentMatcher
import net.bytebuddy.utility.AsmClassReader
import net.bytebuddy.utility.JavaModule
import java.security.ProtectionDomain

/**
 * The type strategy of the method tier: a decoration, which runs the class's received bytes through
 * the visitors added with `visit` and writes the class header as it came, instead of describing the
 * type for a rebase.
 *
 * A decoration refuses `defineField`, `defineMethod` and `initializer`, so what the agent adds to a
 * class is written by [ProbeArrayMembers]. Stock `DECORATE` installs a type attribute appender that
 * resolves the type of every class-level annotation to compare it against the class file; the
 * builder here installs none, since the annotations it would compare are the ones the class file
 * already carries, and resolving a type the classpath lacks costs a parse or a failure.
 *
 * The builder's configuration:
 *
 * - Type validation off. It checks the described type against rules the JVM does not enforce on a
 *   class it has already accepted, and every check it made fired on the adopter's own bytes; the
 *   verifier test in the build covers what this agent writes (ADR 0058).
 * - The method graph of the class's own methods (`ForDeclaredMethods`). The tier weaves declared
 *   methods, builds no bridge and reads no `Implementation.Target`; the default graph resolves every
 *   inherited method's types and merges methods that differ only in return type into one node that
 *   a visitor then wraps once.
 * - No method ignored. ByteBuddy's default ignores every synthetic method, and a Kotlin `$default`
 *   method, which the omission tier weaves, is one; the tier's own matchers decide what is touched.
 * - The writer that refuses to compute a frame ([FrameRefusingClassWriter], ADR 0061).
 * - A disabled implementation context, since nothing is implemented through ByteBuddy.
 *
 * The `ByteBuddy` an `AgentBuilder` hands a type strategy is ignored: its settings are not readable,
 * so this builder carries the configuration itself.
 */
internal object DecoratingTypeStrategy : AgentBuilder.TypeStrategy {
    /** Names auxiliary types only, of which a decoration makes none; the fallback keeps an unknown VM from failing the install. */
    private val classFileVersion: ClassFileVersion = ClassFileVersion.ofThisVm(ClassFileVersion.JAVA_V8)
    private val noMethodsIgnored = LatentMatcher.Resolved<MethodDescription>(ElementMatchers.none())

    override fun builder(
        typeDescription: TypeDescription,
        byteBuddy: ByteBuddy,
        classFileLocator: ClassFileLocator,
        methodNameTransformer: MethodNameTransformer,
        classLoader: ClassLoader?,
        module: JavaModule?,
        protectionDomain: ProtectionDomain?,
    ): DynamicType.Builder<*> = Builder(typeDescription, classFileLocator, classFileVersion, noMethodsIgnored)

    private class Builder(
        type: TypeDescription,
        locator: ClassFileLocator,
        version: ClassFileVersion,
        ignored: LatentMatcher<MethodDescription>,
    ) : DecoratingDynamicTypeBuilder<Any>(
            type,
            TypeAttributeAppender.NoOp.INSTANCE,
            AsmVisitorWrapper.NoOp.INSTANCE,
            version,
            AuxiliaryType.NamingStrategy.SuffixingRandom("auxiliary"),
            AnnotationValueFilter.Default.APPEND_DEFAULTS,
            AnnotationRetention.ENABLED,
            Implementation.Context.Disabled.Factory.INSTANCE,
            MethodGraph.Compiler.ForDeclaredMethods.INSTANCE,
            TypeValidation.DISABLED,
            AsmClassReader.Factory.Default.IMPLICIT,
            FrameRefusingClassWriter,
            ignored,
            emptyList(),
            locator,
        )
}
