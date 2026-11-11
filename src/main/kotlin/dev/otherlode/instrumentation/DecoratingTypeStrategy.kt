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

internal object DecoratingTypeStrategy : AgentBuilder.TypeStrategy {
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
