package dev.otherlode.instrumentation.endpoints

import net.bytebuddy.agent.builder.AgentBuilder
import net.bytebuddy.agent.builder.ResettableClassFileTransformer
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.utility.JavaModule
import java.lang.instrument.Instrumentation
import java.security.ProtectionDomain

/** The bytes of the class the endpoint pipeline is transforming on this thread, if any. */
internal object CurrentClassFile {
    private class Current(
        val internalName: String?,
        val bytes: ByteArray?,
    )

    private val current = ThreadLocal<Current?>()

    /**
     * The bytes being transformed on this thread, when the class being transformed is
     * [internalName], or null otherwise.
     */
    fun bytesOf(internalName: String): ByteArray? = current.get()?.takeIf { it.internalName == internalName }?.bytes

    internal fun <T> during(
        internalName: String?,
        bytes: ByteArray?,
        block: () -> T,
    ): T {
        val previous = current.get()
        current.set(Current(internalName, bytes))
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
 */
internal class CurrentClassFileTransformer(
    private val delegate: ResettableClassFileTransformer,
) : ResettableClassFileTransformer.AbstractBase() {
    override fun transform(
        loader: ClassLoader?,
        className: String?,
        classBeingRedefined: Class<*>?,
        protectionDomain: ProtectionDomain?,
        classfileBuffer: ByteArray?,
    ): ByteArray? =
        CurrentClassFile.during(className, classfileBuffer) {
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
        CurrentClassFile.during(className, classfileBuffer) {
            delegate.transform(module, loader, className, classBeingRedefined, protectionDomain, classfileBuffer)
        }

    override fun iterator(
        typeDescription: TypeDescription,
        classLoader: ClassLoader?,
        module: JavaModule?,
        classBeingRedefined: Class<*>?,
        protectionDomain: ProtectionDomain?,
    ): MutableIterator<AgentBuilder.Transformer> = delegate.iterator(typeDescription, classLoader, module, classBeingRedefined, protectionDomain)

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
