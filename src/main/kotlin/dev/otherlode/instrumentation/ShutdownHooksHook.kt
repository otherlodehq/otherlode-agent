package dev.otherlode.instrumentation

import dev.otherlode.agentLogger
import dev.otherlode.bootstrap.OtherlodeShutdownHooks
import dev.otherlode.shutdown.ShutdownHooksAdvice
import net.bytebuddy.agent.builder.AgentBuilder
import net.bytebuddy.agent.builder.ResettableClassFileTransformer
import net.bytebuddy.asm.Advice
import net.bytebuddy.description.method.MethodDescription
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.DynamicType
import net.bytebuddy.matcher.ElementMatcher
import net.bytebuddy.matcher.ElementMatchers.declaresField
import net.bytebuddy.matcher.ElementMatchers.declaresMethod
import net.bytebuddy.matcher.ElementMatchers.named
import net.bytebuddy.matcher.ElementMatchers.none
import net.bytebuddy.matcher.ElementMatchers.takesArguments
import java.lang.System.Logger.Level
import java.lang.instrument.Instrumentation

/**
 * Weaves the entry of `java.lang.ApplicationShutdownHooks.runHooks` so each run hands the class's
 * `hooks` field to the bootstrap seam in [OtherlodeShutdownHooks]. Install it after the
 * bootstrap holder. When anything fails, it logs a WARNING, tries to undo the weave and [install] returns false.
 *
 * [targetClass] and [loader] name the class to weave and the loader that defines it.
 */
class ShutdownHooksHook(
    private val ownFailures: OwnRetransformationFailures = OwnRetransformationFailures(),
    private val targetClass: String = "java.lang.ApplicationShutdownHooks",
    private val loader: ClassLoader? = null,
) {
    private val log by agentLogger(ShutdownHooksHook::class.java.name)
    private var transformer: ResettableClassFileTransformer? = null

    /** Installs the weave. Returns whether it is on; when it is not, a WARNING says why. */
    fun install(instrumentation: Instrumentation): Boolean {
        var installed: ResettableClassFileTransformer? = null
        try {
            if (!instrumentation.isRetransformClassesSupported) problem("this JVM does not support retransformation")
            if (!BootstrapHolder.isInstalled()) problem("the bootstrap holder is not installed")
            val target = Class.forName(targetClass, false, loader)
            OtherlodeShutdownHooks.hooks()
            addJavaBaseReadEdge(instrumentation, OtherlodeShutdownHooks::class.java)
            val outcome = RetransformationOutcome(targetClass, ownFailures)
            installed = builder(outcome, target).installOn(instrumentation)
            outcome.failure()?.let { problem(it) }
            transformer = installed
            return true
        } catch (t: Throwable) {
            if (installed != null) undo(instrumentation, installed)
            log.log(
                Level.WARNING,
                "otherlode: cannot watch the application's shutdown hooks, so the final flush will not wait for them " +
                    "and code they run after it will not be counted: $t",
            )
            return false
        }
    }

    /** Restores the class's own bytecode. Does nothing if the weave is off. */
    fun uninstall(instrumentation: Instrumentation) {
        val current = transformer ?: return
        transformer = null
        undo(instrumentation, current)
    }

    private fun problem(reason: String): Nothing = throw IllegalStateException(reason)

    private fun builder(
        outcome: RetransformationOutcome,
        target: Class<*>,
    ): AgentBuilder =
        AgentBuilder
            .Default()
            .with(AgentBuilder.TypeStrategy.Default.DECORATE)
            .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
            .disableClassFormatChanges()
            .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
            .with(AgentBuilder.RedefinitionStrategy.DiscoveryStrategy.Explicit(target))
            .with(outcome as AgentBuilder.RedefinitionStrategy.Listener)
            .with(outcome as AgentBuilder.Listener)
            .ignore(none<TypeDescription>())
            .type(named<TypeDescription>(targetClass).and(declaresField(named("hooks"))).and(declaresMethod(runHooks())))
            .transform { builder: DynamicType.Builder<*>, _, _, _, _ ->
                builder.visit(Advice.to(ShutdownHooksAdvice::class.java).on(runHooks()))
            }

    private fun runHooks(): ElementMatcher.Junction<MethodDescription> = named<MethodDescription>("runHooks").and(takesArguments(0))

    private fun undo(
        instrumentation: Instrumentation,
        installed: ResettableClassFileTransformer,
    ) {
        try {
            installed.reset(instrumentation, AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
        } catch (t: Throwable) {
            ownFailures.record(targetClass)
            log.log(Level.WARNING, "otherlode: could not restore $targetClass: $t")
        }
    }
}
