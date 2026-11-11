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

interface HotPathShape {
    fun call(x: Int): Int
}

private fun ClassLoader.classFileOrNull(name: String): ByteArray? =
    getResourceAsStream(name.replace('.', '/') + ".class")?.use { it.readBytes() }

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

class HotPathWeaver {
    val registry = ProbeRegistry()

    private val transformer: ClassFileTransformer = offlineTransformer(listOf(FIXTURE_PACKAGE), registry)

    fun unwoven(fixture: Class<out HotPathShape>): HotPathShape = instantiate(fixture, woven = false)

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
        // Fixtures cannot sit under dev.otherlode., which the agent never instruments.
        const val FIXTURE_PACKAGE = "com.example.hotpath"

        fun classFileOf(type: Class<*>): ByteArray {
            val path = type.name.replace('.', '/') + ".class"
            val stream = checkNotNull(type.classLoader.getResourceAsStream(path)) { "no class file for ${type.name}" }
            return stream.use { it.readBytes() }
        }

        // Calling this points the JVM-wide OtherlodeProbeArrays resolver at registry, so a woven class
        // initialised afterwards takes its array from the most recent call's registry.
        fun offlineTransformer(
            includePackages: List<String>,
            registry: ProbeRegistry,
        ): ClassFileTransformer {
            val captured = mutableListOf<ClassFileTransformer>()
            OtherlodeInstrumentation(
                AgentConfig.parse("includePackages=" + includePackages.joinToString(";")),
                registry,
                captureClassBytes = false,
            ).install(capturing(ByteBuddyAgent.install(), captured))
            return captured.single()
        }

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

class HotPathEndpointSeam {
    val registry = EndpointRegistry()

    val key: Any = java.util.List.of(PATTERN, VERB)

    val entry: EndpointRegistry.EndpointEntry

    init {
        EndpointInstrumentation(registry, emptyList()).install(
            HotPathWeaver.capturing(ByteBuddyAgent.install(), mutableListOf()),
        )
        entry = registry.register(key, MODULE, VERB, PATTERN)
    }

    companion object {
        const val MODULE = "spring-webmvc"

        const val PATTERN = "/owners/{ownerId}"

        const val VERB = "GET"
    }
}
