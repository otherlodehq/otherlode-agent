package dev.otherlode.instrumentation.endpoints

import dev.otherlode.instrumentation.endpoints.api.EndpointModule
import net.bytebuddy.agent.builder.AgentBuilder
import net.bytebuddy.agent.builder.ResettableClassFileTransformer
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.utility.JavaModule
import java.lang.instrument.Instrumentation
import java.security.ProtectionDomain

internal object CurrentClassFile {
    private class Current(
        val internalName: String?,
        val bytes: ByteArray?,
        val candidates: List<EndpointModule>?,
    )

    private val current = ThreadLocal<Current?>()

    fun bytesOf(internalName: String): ByteArray? = current.get()?.takeIf { it.internalName == internalName }?.bytes

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
