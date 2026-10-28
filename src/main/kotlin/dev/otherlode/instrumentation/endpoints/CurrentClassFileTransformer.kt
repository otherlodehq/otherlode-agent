package dev.otherlode.instrumentation.endpoints

import dev.otherlode.instrumentation.endpoints.api.EndpointModule
import net.bytebuddy.agent.builder.AgentBuilder
import net.bytebuddy.agent.builder.ResettableClassFileTransformer
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.utility.JavaModule
import java.lang.instrument.Instrumentation
import java.security.ProtectionDomain

/**
 * The bytes of the class the endpoint pipeline is transforming on this thread, if any, and the
 * modules the pre-filter found could take it.
 */
internal object CurrentClassFile {
    private class Current(
        val internalName: String?,
        val bytes: ByteArray?,
        val candidates: List<EndpointModule>?,
    )

    private val current = ThreadLocal<Current?>()

    /**
     * The bytes being transformed on this thread, when the class being transformed is
     * [internalName], or null otherwise.
     */
    fun bytesOf(internalName: String): ByteArray? = current.get()?.takeIf { it.internalName == internalName }?.bytes

    /**
     * The modules that could take the class being transformed when it is [internalName], or null
     * when this thread has no candidate set for it.
     */
    fun candidatesOf(internalName: String): List<EndpointModule>? = current.get()?.takeIf { it.internalName == internalName }?.candidates

    internal fun <T> during(
        internalName: String?,
        bytes: ByteArray?,
        candidates: List<EndpointModule>? = null,
        block: () -> T,
    ): T {
        val previous = current.get()
        current.set(Current(internalName, bytes, candidates))
        try {
            return block()
        } finally {
            if (previous == null) current.remove() else current.set(previous)
        }
    }
}

/**
 * Wraps [delegate] so that [CurrentClassFile] holds the bytes it is given while it runs. A
 * transform can load another class on the same thread, so the previous bytes are restored after.
 *
 * With a [preFilter], a class no module could take returns null without calling [delegate], and
 * the candidates are recorded beside the bytes for the raw matchers.
 */
internal class CurrentClassFileTransformer(
    private val delegate: ResettableClassFileTransformer,
    private val preFilter: EndpointPreFilter? = null,
) : ResettableClassFileTransformer.AbstractBase() {
    override fun transform(
        loader: ClassLoader?,
        className: String?,
        classBeingRedefined: Class<*>?,
        protectionDomain: ProtectionDomain?,
        classfileBuffer: ByteArray?,
    ): ByteArray? =
        filtered(loader, className, classfileBuffer) {
            delegate.transform(loader, className, classBeingRedefined, protectionDomain, classfileBuffer)
        }

    override fun transform(
        module: Module?,
        loader: ClassLoader?,
        className: String?,
        classBeingRedefined: Class<*>?,
        protectionDomain: ProtectionDomain?,
        classfileBuffer: ByteArray?,
    ): ByteArray? =
        filtered(loader, className, classfileBuffer) {
            delegate.transform(module, loader, className, classBeingRedefined, protectionDomain, classfileBuffer)
        }

    private fun filtered(
        loader: ClassLoader?,
        className: String?,
        classfileBuffer: ByteArray?,
        block: () -> ByteArray?,
    ): ByteArray? {
        val candidates = preFilter?.candidates(className, loader, classfileBuffer)
        if (candidates != null && candidates.isEmpty()) return null
        return CurrentClassFile.during(className, classfileBuffer, candidates, block)
    }

    override fun iterator(
        typeDescription: TypeDescription,
        classLoader: ClassLoader?,
        module: JavaModule?,
        classBeingRedefined: Class<*>?,
        protectionDomain: ProtectionDomain?,
    ): MutableIterator<AgentBuilder.Transformer> =
        delegate.iterator(typeDescription, classLoader, module, classBeingRedefined, protectionDomain)

    override fun reset(
        instrumentation: Instrumentation,
        classFileTransformer: ResettableClassFileTransformer,
        redefinitionStrategy: AgentBuilder.RedefinitionStrategy,
        redefinitionDiscoveryStrategy: AgentBuilder.RedefinitionStrategy.DiscoveryStrategy,
        redefinitionBatchAllocator: AgentBuilder.RedefinitionStrategy.BatchAllocator,
        redefinitionListener: AgentBuilder.RedefinitionStrategy.Listener,
    ): Boolean =
        delegate.reset(
            instrumentation,
            classFileTransformer,
            redefinitionStrategy,
            redefinitionDiscoveryStrategy,
            redefinitionBatchAllocator,
            redefinitionListener,
        )
}
