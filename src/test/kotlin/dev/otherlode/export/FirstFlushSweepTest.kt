package dev.otherlode.export

import dev.otherlode.config.AgentConfig
import dev.otherlode.instrumentation.LoadedClassSweep
import dev.otherlode.instrumentation.OtherlodeInstrumentation
import dev.otherlode.registry.EndpointRegistry
import dev.otherlode.registry.FrameworkMarkerRegistry
import dev.otherlode.registry.ProbeRegistry
import net.bytebuddy.agent.ByteBuddyAgent
import net.bytebuddy.agent.builder.ResettableClassFileTransformer
import java.io.File
import java.lang.instrument.ClassFileTransformer
import java.lang.instrument.Instrumentation
import java.net.URL
import java.net.URLClassLoader
import java.security.ProtectionDomain
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A class the JVM loaded with no transformer offered it is a blind spot the loaded-class sweep
 * exists to report (ADR 0027). The agent's first flush is the first chance to send the report, and
 * a collector with a complete static baseline calls the class never loaded until it arrives. The
 * right behaviour is that the first flush reports it.
 */
class FirstFlushSweepTest {
    private companion object {
        const val OUTER = "com.example.target.BranchTarget"
        const val NESTED = "com.example.target.SampleTarget"
    }

    private val instrumentation: Instrumentation = ByteBuddyAgent.install()
    private var otherlode: OtherlodeInstrumentation? = null
    private var installedTransformer: ResettableClassFileTransformer? = null
    private var trigger: ClassFileTransformer? = null

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
                val loaded = findLoadedClass(name) ?: findClass(name)
                if (resolve) resolveClass(loaded)
                return loaded
            }
        }

        fun loadNestedFromTransform() {
            if (triggered.compareAndSet(false, true)) nested = Class.forName(NESTED, false, this)
        }
    }

    private class CollectingExporter : Exporter {
        val manifests = CopyOnWriteArrayList<ProbeManifest>()
        val deltaBatches = CopyOnWriteArrayList<DeltaBatch>()

        override fun exportDeltaBatch(batch: DeltaBatch) {
            deltaBatches += batch
        }

        override fun exportManifest(manifest: ProbeManifest) {
            manifests += manifest
        }

        override fun exportStaticBaseline(baseline: StaticBaseline) {}

        override fun exportClassListing(listing: ClassListing) {}
    }

    @AfterTest
    fun tearDown() {
        installedTransformer?.let { otherlode?.uninstall(instrumentation, it) }
        trigger?.let { instrumentation.removeTransformer(it) }
        trigger = null
        installedTransformer = null
        otherlode = null
    }

    private fun scenario(): Pair<ExportScheduler, CollectingExporter> {
        val nestedLoadTrigger =
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
        instrumentation.addTransformer(nestedLoadTrigger, false)
        trigger = nestedLoadTrigger

        val config =
            AgentConfig.parse(
                "includePackages=com.example.target,serviceName=checkout,serviceVersion=1.0.0,serviceInstanceId=instance-1,environment=test",
            )
        val registry = ProbeRegistry(confirmsDefinitions = true)
        val instrumented = OtherlodeInstrumentation(config, registry)
        otherlode = instrumented
        installedTransformer = instrumented.install(instrumentation)

        val loader = ReentrantLoader(arrayOf(File("build/classes/java/test").toURI().toURL()), javaClass.classLoader)
        Class.forName(OUTER, true, loader)
        assertTrue(loader.nested != null, "the nested class never loaded, so this test proved nothing")

        val exporter = CollectingExporter()
        val scheduler =
            ExportScheduler(
                config,
                TestResources.forConfig(config),
                registry,
                EndpointRegistry(),
                exporter,
                loadedClassSweep = LoadedClassSweep(instrumentation, registry, EndpointRegistry(), config),
                frameworkMarkers = FrameworkMarkerRegistry(emptyList()),
            )

        return scheduler to exporter
    }

    private fun reportedBy(exporter: CollectingExporter): List<String> = exporter.manifests.flatMap { it.unreportedClasses }.map { it.className }

    @Test
    fun `a class loaded with no transformer offered it is reported by the first flush`() {
        val (scheduler, exporter) = scenario()

        scheduler.flush()

        val reported = reportedBy(exporter)
        assertTrue(
            NESTED in reported,
            "the first flush's manifests report $NESTED as unreported; reported=$reported, manifests=${exporter.manifests.size}, " +
                "delta batches=${exporter.deltaBatches.size}",
        )
    }

    @Test
    fun `control, the same class is reported by the tenth flush`() {
        val (scheduler, exporter) = scenario()

        repeat(10) { scheduler.flush() }

        assertTrue(NESTED in reportedBy(exporter), "the setup reaches the sweep: ${reportedBy(exporter)}")
    }
}
