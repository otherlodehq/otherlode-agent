package dev.otherlode.instrumentation

import dev.otherlode.config.AgentConfig
import dev.otherlode.export.ResourceAttributes
import dev.otherlode.registry.EndpointRegistry
import dev.otherlode.registry.ProbeRegistry
import net.bytebuddy.agent.ByteBuddyAgent
import net.bytebuddy.agent.builder.ResettableClassFileTransformer
import java.io.File
import java.lang.instrument.ClassFileTransformer
import java.lang.instrument.Instrumentation
import java.net.URL
import java.net.URLClassLoader
import java.security.ProtectionDomain
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeflectedClassLoadTest {
    private companion object {
        const val OUTER = "com.example.target.BranchTarget"
        const val NESTED = "com.example.target.SampleTarget"
        const val NOTHING_TO_PROBE = "com.example.target.AbstractOnlyInterface"
    }

    private val instrumentation: Instrumentation = ByteBuddyAgent.install()
    private var otherlode: OtherlodeInstrumentation? = null
    private var installedTransformer: ResettableClassFileTransformer? = null
    private var watcher: ClassFileTransformer? = null
    private var nestedLoadTrigger: ClassFileTransformer? = null

    private class ReentrantLoader(
        urls: Array<URL>,
        parent: ClassLoader,
    ) : URLClassLoader(urls, parent) {
        private val triggered = AtomicBoolean(false)

        @Volatile
        var nested: Class<*>? = null
            private set

        override fun loadClass(
            name: String,
            resolve: Boolean,
        ): Class<*> {
            if (!name.startsWith("com.example.target.")) return super.loadClass(name, resolve)
            synchronized(getClassLoadingLock(name)) {
                val existing = findLoadedClass(name)
                val loaded = existing ?: findClass(name)
                if (resolve) resolveClass(loaded)
                return loaded
            }
        }

        fun loadNestedFromTransform() {
            if (triggered.compareAndSet(false, true)) {
                nested = Class.forName(NESTED, false, this)
            }
        }
    }

    private fun installNestedLoadTrigger() {
        val trigger =
            object : ClassFileTransformer {
                override fun transform(
                    loader: ClassLoader?,
                    className: String?,
                    classBeingRedefined: Class<*>?,
                    protectionDomain: ProtectionDomain?,
                    classfileBuffer: ByteArray,
                ): ByteArray? {
                    if (className == OUTER.replace('.', '/') && loader is ReentrantLoader) loader.loadNestedFromTransform()
                    return null
                }
            }
        instrumentation.addTransformer(trigger, false)
        trigger.also { nestedLoadTrigger = it }
    }

    @AfterTest
    fun tearDown() {
        installedTransformer?.let { otherlode?.uninstall(instrumentation, it) }
        watcher?.let { instrumentation.removeTransformer(it) }
        nestedLoadTrigger?.let { instrumentation.removeTransformer(it) }
        nestedLoadTrigger = null
        installedTransformer = null
        watcher = null
        otherlode = null
    }

    @Test
    fun `a class that loads inside another class's transform reaches no transformer at all`() {
        val seen = mutableListOf<String>()
        val spy =
            object : ClassFileTransformer {
                override fun transform(
                    loader: ClassLoader?,
                    className: String?,
                    classBeingRedefined: Class<*>?,
                    protectionDomain: ProtectionDomain?,
                    classfileBuffer: ByteArray,
                ): ByteArray? {
                    if (className != null && className.startsWith("com/example/target/")) {
                        synchronized(seen) { seen += className }
                    }
                    return null
                }
            }
        instrumentation.addTransformer(spy, false)
        watcher = spy
        installNestedLoadTrigger()

        val registry = ProbeRegistry()
        val instrumented = OtherlodeInstrumentation(AgentConfig.parse("includePackages=com.example.target"), registry)
        otherlode = instrumented
        installedTransformer = instrumented.install(instrumentation)

        val loader = ReentrantLoader(arrayOf(File("build/classes/java/test").toURI().toURL()), javaClass.classLoader)
        Class.forName(OUTER, true, loader)

        val nested = loader.nested
        assertTrue(nested != null, "the nested class never loaded, so this test proved nothing")
        assertEquals(loader, nested.classLoader, "the nested class has to be this loader's own copy")
        assertEquals(listOf("com/example/target/BranchTarget"), synchronized(seen) { seen.toList() })

        val manifest = registry.manifest(ResourceAttributes("test", null, "instance-1", null, "run-1"))
        assertTrue(manifest.probes.none { it.className == NESTED }, "a class no transformer saw cannot have probes")
        assertTrue(manifest.skippedClasses.none { it.className == NESTED }, "and cannot be recorded as skipped either")
    }

    @Test
    fun `the sweep reports the deflected class, and does not report one with nothing to probe`() {
        installNestedLoadTrigger()
        val registry = ProbeRegistry()
        val config = AgentConfig.parse("includePackages=com.example.target")
        val instrumented = OtherlodeInstrumentation(config, registry)
        otherlode = instrumented
        installedTransformer = instrumented.install(instrumentation)

        val loader = ReentrantLoader(arrayOf(File("build/classes/java/test").toURI().toURL()), javaClass.classLoader)
        Class.forName(OUTER, true, loader)
        Class.forName(NOTHING_TO_PROBE, true, loader)
        assertTrue(loader.nested != null, "the nested class never loaded, so this test proved nothing")

        LoadedClassSweep(instrumentation, registry, EndpointRegistry(), config).run(runForwardPass = true)

        val manifest = registry.manifest(ResourceAttributes("test", null, "instance-1", null, "run-1"))
        val unreported = manifest.unreportedClasses.map { it.className }
        assertTrue(NESTED in unreported, "the deflected class is what the sweep exists to find")
        assertTrue(
            NOTHING_TO_PROBE !in unreported,
            "a class the agent looked at and found nothing in is accounted for, not a blind spot",
        )
        assertTrue(
            OUTER !in unreported,
            "a class that registered normally is accounted for",
        )
    }

    @Test
    fun `a sweep whose include prefixes cover the JDK does not name the JDK`() {
        // ByteBuddy ignores the bootstrap and platform loaders and its own classes before any type matcher runs,
        // so those classes reach no transformer by design and are not a blind spot.
        val registry = ProbeRegistry()
        val config = AgentConfig.parse("includePackages=java;jdk;sun;com.sun;net.bytebuddy;org.junit;org.gradle")

        LoadedClassSweep(instrumentation, registry, EndpointRegistry(), config).run(runForwardPass = true)

        val unreported =
            registry
                .manifest(
                    ResourceAttributes("test", null, "instance-1", null, "run-1"),
                ).unreportedClasses
                .map { it.className }

        // This JVM is shared with every other test and installs the agent part-way through, so classes on the
        // application loader may have loaded unreported, jdk.attach and jdk.internal.jvmstat among them.
        // java.base is always bootstrap, so it tests the gate.
        assertTrue(
            unreported.none { it.startsWith("java.lang.") || it.startsWith("java.util.") || it.startsWith("java.io.") },
            "the bootstrap loader reaches no transformer by design: $unreported",
        )
        assertTrue(unreported.none { it.startsWith("net.bytebuddy.") }, "ByteBuddy ignores its own classes")
        assertTrue(unreported.none { it.startsWith("[") }, "an array class is never offered to a transformer either")
        assertTrue(unreported.none { "/0x" in it }, "a hidden class carries a name nothing could join on")
    }
}
