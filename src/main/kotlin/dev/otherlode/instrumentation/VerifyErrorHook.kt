package dev.otherlode.instrumentation

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
 * `VerifyError` is not loaded at `premain` on every JDK, so the hook loads it and retransforms it,
 * as [dev.otherlode.instrumentation.endpoints.LambdaFactoryHook] does for the lambda factory. It
 * is installed once the bootstrap holder is. When anything fails it logs one WARNING, installs
 * nothing, and [watch] says the run is not watched.
 *
 * [targetClass] names the class to weave; a test names another to make the install fail.
 */
class VerifyErrorHook(
    private val ownFailures: OwnRetransformationFailures = OwnRetransformationFailures(),
    private val targetClass: String = "java.lang.VerifyError",
) {
    private val log = System.getLogger(VerifyErrorHook::class.java.name)
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
            addJavaBaseReadEdge(instrumentation)
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

    /**
     * Constructs one `VerifyError` through each constructor, so the woven path has resolved its
     * references before a class fails to link under a lock, checks the weave took by finding
     * [message] in the ring, and returns everything the drain read. [message] differs on each
     * install, since the ring keeps a message equal to one it already stored only once. Another
     * thread may have built a `VerifyError` since the weave went live, so the caller keeps what is
     * not this check's own.
     */
    private fun warmWovenPaths(message: String): Array<String> {
        VerifyError(message)
        VerifyError()
        val drained = OtherlodeVerifierMessages.drain()
        check(drained.contains(message)) { "a VerifyError built after the weave was not recorded" }
        return drained
    }

    private fun addJavaBaseReadEdge(instrumentation: Instrumentation) {
        val seamModule = OtherlodeVerifierMessages::class.java.module
        val javaBase = Any::class.java.module
        instrumentation.redefineModule(javaBase, setOf(seamModule), emptyMap(), emptyMap(), emptySet(), emptyMap())
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
