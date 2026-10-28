package dev.otherlode.instrumentation.endpoints

import dev.otherlode.instrumentation.endpoints.api.AdviceBinder
import dev.otherlode.instrumentation.endpoints.api.EndpointModule
import net.bytebuddy.agent.builder.AgentBuilder
import net.bytebuddy.agent.builder.ResettableClassFileTransformer
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.DynamicType
import net.bytebuddy.matcher.ElementMatcher
import net.bytebuddy.matcher.ElementMatchers.any
import net.bytebuddy.utility.JavaModule
import java.lang.instrument.Instrumentation
import java.security.ProtectionDomain
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EndpointPreFilterTest {
    private class StubModule(
        override val name: String = "stub-${System.nanoTime()}",
        override val typeNames: Set<String>? = null,
        private val loaderAccepts: Boolean = true,
        private val loaderThrows: Boolean = false,
        private val bytesAccept: Boolean = true,
        private val bytesThrow: Boolean = false,
        private val match: Boolean = false,
    ) : EndpointModule {
        val loaderMatcherCalls = AtomicInteger()
        val loaderMatches = AtomicInteger()
        val byteChecks = AtomicInteger()
        val typeMatcherCalls = AtomicInteger()

        override fun classLoaderMatcher(): ElementMatcher<in ClassLoader> {
            loaderMatcherCalls.incrementAndGet()
            return ElementMatcher {
                loaderMatches.incrementAndGet()
                if (loaderThrows) error("gate failed")
                loaderAccepts
            }
        }

        override fun classFileMayMatch(classFile: ByteArray): Boolean {
            byteChecks.incrementAndGet()
            if (bytesThrow) error("byte search failed")
            return bytesAccept
        }

        override fun typeMatcher(): ElementMatcher<in TypeDescription> {
            typeMatcherCalls.incrementAndGet()
            return if (match) any() else ElementMatcher { false }
        }

        override fun transform(
            builder: DynamicType.Builder<*>,
            typeDescription: TypeDescription,
            advice: AdviceBinder,
            classLoader: ClassLoader?,
        ): DynamicType.Builder<*> = builder
    }

    private class CountingDelegate(
        private val onTransform: () -> Unit = {},
    ) : ResettableClassFileTransformer.AbstractBase() {
        val calls = AtomicInteger()

        override fun transform(
            loader: ClassLoader?,
            className: String?,
            classBeingRedefined: Class<*>?,
            protectionDomain: ProtectionDomain?,
            classfileBuffer: ByteArray?,
        ): ByteArray? {
            calls.incrementAndGet()
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
        ): Boolean = true
    }

    private val bytes = byteArrayOf(1, 2, 3)

    private fun transformerOver(
        delegate: CountingDelegate,
        vararg modules: EndpointModule,
    ) = CurrentClassFileTransformer(delegate, EndpointPreFilter(modules.toList()))

    @Test
    fun `a class no module names on a loader every gate rejects never reaches the delegate`() {
        val delegate = CountingDelegate()
        val named = StubModule(typeNames = setOf("a.B"), loaderAccepts = false)
        val any = StubModule(typeNames = null, loaderAccepts = false)

        val result = transformerOver(delegate, named, any).transform(null, "x/Y", null, null, bytes)

        assertNull(result)
        assertEquals(0, delegate.calls.get())
    }

    @Test
    fun `a class named by no module never reaches the delegate even when the loader gate accepts`() {
        val delegate = CountingDelegate()

        transformerOver(delegate, StubModule(typeNames = setOf("a.B"))).transform(null, "x/Y", null, null, bytes)

        assertEquals(0, delegate.calls.get())
    }

    @Test
    fun `a name in a module's set reaches the delegate`() {
        val delegate = CountingDelegate()

        transformerOver(delegate, StubModule(typeNames = setOf("a.B"))).transform(null, "a/B", null, null, bytes)

        assertEquals(1, delegate.calls.get())
    }

    @Test
    fun `a name in a module's set does not reach the delegate when the module's loader gate rejects the loader`() {
        val delegate = CountingDelegate()

        transformerOver(delegate, StubModule(typeNames = setOf("a.B"), loaderAccepts = false)).transform(null, "a/B", null, null, bytes)

        assertEquals(0, delegate.calls.get())
    }

    @Test
    fun `a module with no name set whose gate accepts reaches the delegate only when the byte check is true`() {
        val yes = CountingDelegate()
        val no = CountingDelegate()

        transformerOver(yes, StubModule(bytesAccept = true)).transform(null, "x/Y", null, null, bytes)
        transformerOver(no, StubModule(bytesAccept = false)).transform(null, "x/Y", null, null, bytes)

        assertEquals(1, yes.calls.get())
        assertEquals(0, no.calls.get())
    }

    @Test
    fun `a module with no name set whose gate rejects does not run its byte check`() {
        val module = StubModule(loaderAccepts = false)

        transformerOver(CountingDelegate(), module).transform(null, "x/Y", null, null, bytes)

        assertEquals(0, module.byteChecks.get())
    }

    @Test
    fun `a class without bytes is a candidate for a module whose gate accepts, with no byte check`() {
        val delegate = CountingDelegate()
        val module = StubModule()

        transformerOver(delegate, module).transform(null, "x/Y", null, null, null)

        assertEquals(1, delegate.calls.get())
        assertEquals(0, module.byteChecks.get())
    }

    @Test
    fun `each loader matcher is asked once, at install`() {
        val module = StubModule(typeNames = setOf("a.B"))
        val transformer = transformerOver(CountingDelegate(), module)

        repeat(3) { transformer.transform(null, "a/B", null, null, bytes) }

        assertEquals(1, module.loaderMatcherCalls.get())
        assertEquals(3, module.loaderMatches.get())
    }

    @Test
    fun `a module whose gate throws makes the class reach the delegate`() {
        val delegate = CountingDelegate()

        transformerOver(delegate, StubModule(loaderThrows = true)).transform(null, "x/Y", null, null, bytes)

        assertEquals(1, delegate.calls.get())
    }

    @Test
    fun `a module whose byte check throws makes the class reach the delegate`() {
        val delegate = CountingDelegate()

        transformerOver(delegate, StubModule(bytesThrow = true)).transform(null, "x/Y", null, null, bytes)

        assertEquals(1, delegate.calls.get())
    }

    @Test
    fun `a null class name reaches the delegate`() {
        val delegate = CountingDelegate()

        transformerOver(delegate, StubModule(typeNames = setOf("a.B"), loaderAccepts = false)).transform(null, null, null, null, bytes)

        assertEquals(1, delegate.calls.get())
    }

    @Test
    fun `the module overload filters too`() {
        val delegate = CountingDelegate()
        val transformer = transformerOver(delegate, StubModule(typeNames = setOf("a.B")))

        transformer.transform(Any::class.java.module, null, "x/Y", null, null, bytes)
        transformer.transform(Any::class.java.module, null, "a/B", null, null, bytes)

        assertEquals(1, delegate.calls.get())
    }

    @Test
    fun `the candidates are recorded beside the bytes while the delegate runs`() {
        val named = StubModule(typeNames = setOf("a.B"))
        val other = StubModule(typeNames = setOf("c.D"))
        val anyName = StubModule(typeNames = null)
        var seen: List<EndpointModule>? = null
        val delegate = CountingDelegate { seen = CurrentClassFile.candidatesOf("a/B") }

        transformerOver(delegate, named, other, anyName).transform(null, "a/B", null, null, bytes)

        assertEquals(setOf(named, anyName), seen?.toSet())
        assertNull(CurrentClassFile.candidatesOf("a/B"))
    }

    @Test
    fun `a transformer with no pre-filter always delegates and records no candidates`() {
        var seen: List<EndpointModule>? = listOf()
        val delegate = CountingDelegate { seen = CurrentClassFile.candidatesOf("a/B") }

        CurrentClassFileTransformer(delegate).transform(null, "a/B", null, null, bytes)

        assertEquals(1, delegate.calls.get())
        assertNull(seen)
    }

    @Test
    fun `a raw matcher answers false at once for a module outside the recorded candidates`() {
        val module = StubModule(match = true)
        val matcher = EndpointInstrumentation.rawMatcher(module)
        val type = TypeDescription.ForLoadedType.of(String::class.java)
        val loaderMatchesBefore = module.loaderMatches.get()

        val answer =
            CurrentClassFile.during("java/lang/String", bytes, listOf(StubModule())) {
                matcher.matches(type, null, null, null, null)
            }

        assertFalse(answer)
        assertEquals(loaderMatchesBefore, module.loaderMatches.get())
        assertEquals(0, module.byteChecks.get())
        assertEquals(0, module.typeMatcherCalls.get())
    }

    @Test
    fun `a raw matcher runs its own checks for a module among the recorded candidates`() {
        val module = StubModule(match = true)
        val matcher = EndpointInstrumentation.rawMatcher(module)
        val type = TypeDescription.ForLoadedType.of(String::class.java)

        val answer =
            CurrentClassFile.during("java/lang/String", bytes, listOf(module)) {
                matcher.matches(type, null, null, null, null)
            }

        assertTrue(answer)
        assertEquals(1, module.loaderMatches.get())
        assertEquals(1, module.byteChecks.get())
    }

    @Test
    fun `a raw matcher with no current class behaves as before`() {
        val module = StubModule(match = true)
        val matcher = EndpointInstrumentation.rawMatcher(module)
        val type = TypeDescription.ForLoadedType.of(String::class.java)

        assertTrue(matcher.matches(type, null, null, null, null))
        assertEquals(1, module.loaderMatches.get())
        assertEquals(0, module.byteChecks.get())
    }

    @Test
    fun `a recorded candidate set for another class does not apply`() {
        val module = StubModule(match = true)
        val matcher = EndpointInstrumentation.rawMatcher(module)
        val type = TypeDescription.ForLoadedType.of(String::class.java)

        val answer = CurrentClassFile.during("a/Other", bytes, emptyList()) { matcher.matches(type, null, null, null, null) }

        assertTrue(answer)
    }

    @Test
    fun `candidatesOf returns the very list recorded`() {
        val recorded = listOf<EndpointModule>(StubModule())
        val seen = CurrentClassFile.during("a/B", bytes, recorded) { CurrentClassFile.candidatesOf("a/B") }

        assertSame(recorded, seen)
    }
}
