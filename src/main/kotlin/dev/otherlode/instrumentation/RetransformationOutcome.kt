package dev.otherlode.instrumentation

import net.bytebuddy.agent.builder.AgentBuilder
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.DynamicType
import net.bytebuddy.utility.JavaModule

internal class RetransformationOutcome(
    private val target: String,
    private val ownFailures: OwnRetransformationFailures,
) : AgentBuilder.Listener.Adapter(),
    AgentBuilder.RedefinitionStrategy.Listener {
    private var transformed = false
    private var error: String? = null

    fun failure(): String? = error ?: if (transformed) null else "$target was not transformed"

    override fun onTransformation(
        typeDescription: TypeDescription,
        classLoader: ClassLoader?,
        module: JavaModule?,
        loaded: Boolean,
        dynamicType: DynamicType,
    ) {
        if (typeDescription.name == target) transformed = true
    }

    override fun onError(
        typeName: String,
        classLoader: ClassLoader?,
        module: JavaModule?,
        loaded: Boolean,
        throwable: Throwable,
    ) {
        ownFailures.record(typeName)
        if (error == null) error = "transforming $typeName failed: $throwable"
    }

    override fun onBatch(
        index: Int,
        batch: List<Class<*>>,
        types: List<Class<*>>,
    ) {}

    override fun onError(
        index: Int,
        batch: List<Class<*>>,
        throwable: Throwable,
        types: List<Class<*>>,
    ): Iterable<List<Class<*>>> {
        batch.forEach { ownFailures.record(it.name) }
        if (error == null) error = "retransforming $target failed: $throwable"
        return emptyList()
    }

    override fun onComplete(
        amount: Int,
        types: List<Class<*>>,
        failures: Map<List<Class<*>>, Throwable>,
    ) {
        failures.keys.forEach { batch -> batch.forEach { ownFailures.record(it.name) } }
        if (error == null && failures.isNotEmpty()) {
            error = "retransforming $target failed: ${failures.values.first()}"
        }
    }
}
