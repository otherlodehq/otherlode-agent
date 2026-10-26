package dev.otherlode.instrumentation.endpoints

import net.bytebuddy.agent.builder.AgentBuilder
import net.bytebuddy.agent.builder.ResettableClassFileTransformer
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.utility.JavaModule
import java.lang.instrument.Instrumentation
import java.lang.reflect.Proxy
import java.security.ProtectionDomain
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class CurrentClassFileTransformerTest {
    private class StubDelegate(
        private val onTransform: () -> Unit = {},
    ) : ResettableClassFileTransformer.AbstractBase() {
        var resetWith: ResettableClassFileTransformer? = null

        override fun transform(
            loader: ClassLoader?,
            className: String?,
            classBeingRedefined: Class<*>?,
            protectionDomain: ProtectionDomain?,
            classfileBuffer: ByteArray?,
        ): ByteArray? {
            onTransform()
            return null
        }

        override fun iterator(
            typeDescription: TypeDescription,
            classLoader: ClassLoader?,
            module: JavaModule?,
            classBeingRedefined: Class<*>?,
            protectionDomain: ProtectionDomain?,
        ): MutableIterator<AgentBuilder.Transformer> = mutableListOf<AgentBuilder.Transformer>().iterator()

        override fun reset(
            instrumentation: Instrumentation,
            classFileTransformer: ResettableClassFileTransformer,
            redefinitionStrategy: AgentBuilder.RedefinitionStrategy,
            redefinitionDiscoveryStrategy: AgentBuilder.RedefinitionStrategy.DiscoveryStrategy,
            redefinitionBatchAllocator: AgentBuilder.RedefinitionStrategy.BatchAllocator,
            redefinitionListener: AgentBuilder.RedefinitionStrategy.Listener,
        ): Boolean {
            resetWith = classFileTransformer
            return true
        }
    }

    private val noInstrumentation =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Instrumentation::class.java)) { _, _, _ ->
            error("not called")
        } as Instrumentation

    @Test
    fun `the bytes being transformed are readable while the delegate runs`() {
        val outer = byteArrayOf(1, 2, 3)
        var seen: ByteArray? = null
        val wrapper = CurrentClassFileTransformer(StubDelegate { seen = CurrentClassFile.bytesOf("a/B") })

        wrapper.transform(null, "a/B", null, null, outer)

        assertContentEquals(outer, seen)
        assertNull(CurrentClassFile.bytesOf("a/B"))
    }

    @Test
    fun `the module overload exposes the bytes too`() {
        val outer = byteArrayOf(7)
        var seen: ByteArray? = null
        val wrapper = CurrentClassFileTransformer(StubDelegate { seen = CurrentClassFile.bytesOf("a/B") })

        wrapper.transform(Any::class.java.module, null, "a/B", null, null, outer)

        assertContentEquals(outer, seen)
        assertNull(CurrentClassFile.bytesOf("a/B"))
    }

    @Test
    fun `the bytes are given only for the class being transformed`() {
        var seen: ByteArray? = byteArrayOf()
        val wrapper = CurrentClassFileTransformer(StubDelegate { seen = CurrentClassFile.bytesOf("a/Other") })

        wrapper.transform(null, "a/B", null, null, byteArrayOf(1))

        assertNull(seen)
    }

    @Test
    fun `a nested transform restores the outer bytes`() {
        val outer = byteArrayOf(1)
        val inner = byteArrayOf(2)
        val afterInner = mutableListOf<ByteArray?>()
        lateinit var wrapper: CurrentClassFileTransformer
        var depth = 0
        wrapper =
            CurrentClassFileTransformer(
                StubDelegate {
                    if (depth++ == 0) {
                        wrapper.transform(null, "a/Inner", null, null, inner)
                        afterInner += CurrentClassFile.bytesOf("a/Outer")
                    }
                },
            )

        wrapper.transform(null, "a/Outer", null, null, outer)

        assertSame(outer, afterInner.single())
        assertNull(CurrentClassFile.bytesOf("a/Outer"))
    }

    @Test
    fun `the bytes are cleared when the delegate throws`() {
        val wrapper = CurrentClassFileTransformer(StubDelegate { error("boom") })

        runCatching { wrapper.transform(null, "a/B", null, null, byteArrayOf(1)) }

        assertNull(CurrentClassFile.bytesOf("a/B"))
    }

    @Test
    fun `a reset asks the delegate to remove the wrapper`() {
        val delegate = StubDelegate()
        val wrapper = CurrentClassFileTransformer(delegate)

        wrapper.reset(noInstrumentation, AgentBuilder.RedefinitionStrategy.DISABLED)

        assertSame(wrapper, delegate.resetWith)
    }

    @Test
    fun `a reset given a transformer passes that transformer on`() {
        val delegate = StubDelegate()
        val outer = StubDelegate()

        CurrentClassFileTransformer(delegate).reset(
            noInstrumentation,
            outer,
            AgentBuilder.RedefinitionStrategy.DISABLED,
            AgentBuilder.RedefinitionStrategy.DiscoveryStrategy.SinglePass.INSTANCE,
            AgentBuilder.RedefinitionStrategy.BatchAllocator.ForTotal.INSTANCE,
            AgentBuilder.RedefinitionStrategy.Listener.NoOp.INSTANCE,
        )

        assertSame(outer, delegate.resetWith)
    }
}
