package dev.otherlode.instrumentation

import dev.otherlode.agentLogger
import dev.otherlode.bootstrap.OtherlodeVerifierMessages
import dev.otherlode.verifier.VerifyErrorAdvice
import net.bytebuddy.agent.builder.AgentBuilder
import net.bytebuddy.agent.builder.ResettableClassFileTransformer
import net.bytebuddy.asm.Advice
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.DynamicType
import net.bytebuddy.matcher.ElementMatchers.isConstructor
import net.bytebuddy.matcher.ElementMatchers.named
import net.bytebuddy.matcher.ElementMatchers.none
import java.lang.System.Logger.Level
import java.lang.instrument.Instrumentation

/**
 * Weaves both constructors of `java.lang.VerifyError` so each construction hands its message
 * argument to the bootstrap ring in [OtherlodeVerifierMessages].
 *
 * It needs the bootstrap holder installed. When anything fails it logs a WARNING, tries to undo
 * any weave it made, and [watch] says the run is not watched.
 */
class VerifyErrorHook(
    private val ownFailures: OwnRetransformationFailures = OwnRetransformationFailures(),
    private val targetClass: String = "java.lang.VerifyError",
) {
    private val log by agentLogger(VerifyErrorHook::class.java.name)
    private var transformer: ResettableClassFileTransformer? = null
    private var watch = VerifierRejectionWatch.NOT_WATCHED

    /** What the run can see: watched once [install] succeeded, not watched otherwise. */
    fun watch(): VerifierRejectionWatch = watch

    /** Installs the hook. Returns whether it is on; when it is not, one WARNING says why. */
    fun install(instrumentation: Instrumentation): Boolean {
        var installed: ResettableClassFileTransformer? = null
        try {
            if (!instrumentation.isRetransformClassesSupported) problem("this JVM does not support retransformation")
            if (!BootstrapHolder.isInstalled()) problem("the bootstrap holder is not installed")
            val target = Class.forName(targetClass, false, null)
            OtherlodeVerifierMessages.warm()
            addJavaBaseReadEdge(instrumentation, OtherlodeVerifierMessages::class.java)
            val outcome = RetransformationOutcome(targetClass, ownFailures)
            installed = builder(outcome, target).installOn(instrumentation)
            outcome.failure()?.let { problem(it) }
            val checkMessage = "$WARM_MESSAGE ${System.nanoTime()}"
            val drained = warmWovenPaths(checkMessage)
            transformer = installed
            watch =
                VerifierRejectionWatch(
                    watched = true,
                    ownFailures = ownFailures,
                    carried = drained.filter { it != checkMessage }.toTypedArray(),
                    carriedOverflow = OtherlodeVerifierMessages.takeOverflow(),
                )
            return true
        } catch (t: Throwable) {
            if (installed != null) undo(instrumentation, installed)
            watch = VerifierRejectionWatch.NOT_WATCHED
            log.log(
                Level.WARNING,
                "otherlode: classes the JVM's verifier rejects will not be named on this JVM, " +
                    "so a run's zero counts say nothing about them: $t",
            )
            return false
        }
    }

    /** Restores `VerifyError`'s own bytecode. Does nothing if the hook is off. */
    fun uninstall(instrumentation: Instrumentation) {
        val current = transformer ?: return
        transformer = null
        watch = VerifierRejectionWatch.NOT_WATCHED
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
            .type(named<TypeDescription>(targetClass))
            .transform { builder: DynamicType.Builder<*>, _, _, _, _ ->
                builder.visit(Advice.to(VerifyErrorAdvice::class.java).on(isConstructor()))
            }

    // Builds through both constructors so the woven path resolves its references before a class fails
    // to link under a lock. The ring stores an equal message only once, so each install's message differs.
    private fun warmWovenPaths(message: String): Array<String> {
        VerifyError(message)
        VerifyError()
        val drained = OtherlodeVerifierMessages.drain()
        check(drained.contains(message)) { "a VerifyError built after the weave was not recorded" }
        return drained
    }

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

    private companion object {
        const val WARM_MESSAGE = "otherlode: warming the VerifyError hook"
    }
}
