package dev.otherlode.benchmark

import dev.otherlode.config.AgentConfig
import dev.otherlode.instrumentation.OtherlodeInstrumentation
import dev.otherlode.instrumentation.endpoints.EndpointInstrumentation
import dev.otherlode.registry.EndpointRegistry
import dev.otherlode.registry.ProbeRegistry
import net.bytebuddy.agent.ByteBuddyAgent
import java.lang.instrument.ClassFileTransformer
import java.lang.instrument.Instrumentation
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/** What every hot-path fixture implements, so a benchmark calls it through an interface the fixture's loader shares. */
interface HotPathShape {
    /** Runs the fixture's measured work for [x] and returns a value that depends on all of it. */
    fun call(x: Int): Int
}

private fun ClassLoader.classFileOrNull(name: String): ByteArray? =
    getResourceAsStream(name.replace('.', '/') + ".class")?.use { it.readBytes() }

/**
 * Defines the classes under [fixturePrefix] itself, child-first, from [bytes] or else from the
 * parent's class file, so a fixture's nested classes load beside it unwoven. Every other name, the
 * [HotPathShape] interface and the Kotlin stdlib among them, resolves from the parent. A class file
 * stays readable as a resource through the parent, which the analysis reads it from.
 */
class FixtureClassLoader(
    parent: ClassLoader,
    private val fixturePrefix: String,
    private val bytes: MutableMap<String, ByteArray>,
) : ClassLoader(parent) {
    override fun loadClass(
        name: String,
        resolve: Boolean,
    ): Class<*> =
        synchronized(getClassLoadingLock(name)) {
            val own = if (name.startsWith(fixturePrefix)) bytes[name] ?: parent.classFileOrNull(name) else null
            if (own == null) {
                super.loadClass(name, resolve)
            } else {
                findLoadedClass(name) ?: defineClass(name, own, 0, own.size)
            }
        }
}

/**
 * Hands out two copies of one fixture class from the same bytes: [unwoven] as the compiler wrote
 * it, and [woven] after the agent's own transformer has rewritten it. Each copy lives in its own
 * [FixtureClassLoader], so the two never share a probe array or a loaded class.
 *
 * The transformer is the real one: [OtherlodeInstrumentation] installs against an
 * [Instrumentation] that passes every call to the JVM's own except `addTransformer`, whose
 * transformer it keeps and calls directly. Fixtures sit under [FIXTURE_PACKAGE], which the agent is
 * configured to include. The registry that receives the woven classes' probes is [registry].
 */
class HotPathWeaver {
    /** Where each woven class registers its probes, and where a test reads their counts. */
    val registry = ProbeRegistry()

    private val transformer: ClassFileTransformer = offlineTransformer(listOf(FIXTURE_PACKAGE), registry)

    /** A new instance of [fixture], defined from its class file exactly as the compiler wrote it. */
    fun unwoven(fixture: Class<out HotPathShape>): HotPathShape = instantiate(fixture, woven = false)

    /** A new instance of [fixture], defined from the bytes the agent's transformer returns for its class file. */
    fun woven(fixture: Class<out HotPathShape>): HotPathShape = instantiate(fixture, woven = true)

    private fun instantiate(
        fixture: Class<out HotPathShape>,
        woven: Boolean,
    ): HotPathShape {
        val original = classFileOf(fixture)
        val bytes = mutableMapOf<String, ByteArray>()
        val loader = FixtureClassLoader(fixture.classLoader, FIXTURE_PACKAGE, bytes)
        bytes[fixture.name] =
            if (woven) {
                checkNotNull(transformer.transform(loader, fixture.name.replace('.', '/'), null, null, original)) {
                    "the agent's transformer left ${fixture.name} unchanged; is it under $FIXTURE_PACKAGE?"
                }
            } else {
                original
            }
        return loader.loadClass(fixture.name).getDeclaredConstructor().newInstance() as HotPathShape
    }

    companion object {
        /** The package every fixture lives in. The agent never instruments its own `dev.otherlode.` package, so it cannot be under that. */
        const val FIXTURE_PACKAGE = "com.example.hotpath"

        /** The class file of [type], read as a resource through its own loader. */
        fun classFileOf(type: Class<*>): ByteArray {
            val path = type.name.replace('.', '/') + ".class"
            val stream = checkNotNull(type.classLoader.getResourceAsStream(path)) { "no class file for ${type.name}" }
            return stream.use { it.readBytes() }
        }

        /**
         * The agent's own class file transformer, configured to include [includePackages] and
         * registering what it weaves in [registry]. It is not registered with the JVM: a caller
         * invokes `transform` itself, so nothing else the JVM loads passes through it. Installing
         * it still points the JVM-wide `OtherlodeProbeArrays` resolver at [registry], so a woven
         * class initialised afterwards takes its array from the most recent call's registry.
         * [describeMissingTypes] false weaves with ByteBuddy's own type pool, for comparing bytes.
         */
        fun offlineTransformer(
            includePackages: List<String>,
            registry: ProbeRegistry,
            describeMissingTypes: Boolean = true,
        ): ClassFileTransformer {
            val captured = mutableListOf<ClassFileTransformer>()
            OtherlodeInstrumentation(
                AgentConfig.parse("includePackages=" + includePackages.joinToString(";")),
                registry,
                captureClassBytes = false,
                describeMissingTypes = describeMissingTypes,
            ).install(capturing(ByteBuddyAgent.install(), captured))
            return captured.single()
        }

        /** An [Instrumentation] that forwards to [real] except `addTransformer`, whose argument it adds to [sink] instead. */
        fun capturing(
            real: Instrumentation,
            sink: MutableList<ClassFileTransformer>,
        ): Instrumentation =
            Proxy.newProxyInstance(Instrumentation::class.java.classLoader, arrayOf(Instrumentation::class.java)) { _, method, args ->
                if (method.name == "addTransformer") {
                    sink += args[0] as ClassFileTransformer
                    null
                } else {
                    try {
                        method.invoke(real, *(args ?: emptyArray()))
                    } catch (e: InvocationTargetException) {
                        throw e.targetException
                    }
                }
            } as Instrumentation
    }
}

/**
 * The endpoint seam with the agent's real resolver installed over a real [EndpointRegistry], and
 * one entry registered for the module [MODULE] under the key [key]. Installing goes through
 * [EndpointInstrumentation] with no modules, so no framework class is transformed.
 */
class HotPathEndpointSeam {
    /** The registry the installed resolver reads and writes. */
    val registry = EndpointRegistry()

    /** The dispatch key `HandleMatchAdvice` builds for [PATTERN] and [VERB]. */
    val key: Any = java.util.List.of(PATTERN, VERB)

    /** The entry registered for [key]. */
    val entry: EndpointRegistry.EndpointEntry

    init {
        EndpointInstrumentation(registry, emptyList()).install(
            HotPathWeaver.capturing(ByteBuddyAgent.install(), mutableListOf()),
        )
        entry = registry.register(key, MODULE, VERB, PATTERN)
    }

    companion object {
        /** The module name `HandleMatchAdvice` passes to the seam. */
        const val MODULE = "spring-webmvc"

        /** The route template of the registered entry. */
        const val PATTERN = "/owners/{ownerId}"

        /** The verb of the registered entry. */
        const val VERB = "GET"
    }
}
