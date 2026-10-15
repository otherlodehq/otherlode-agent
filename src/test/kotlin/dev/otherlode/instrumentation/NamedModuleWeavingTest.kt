package dev.otherlode.instrumentation

import dev.otherlode.config.AgentConfig
import dev.otherlode.export.ProbeKind
import dev.otherlode.export.ResourceAttributes
import dev.otherlode.registry.ProbeRegistry
import net.bytebuddy.agent.ByteBuddyAgent
import net.bytebuddy.agent.builder.ResettableClassFileTransformer
import org.junit.jupiter.api.io.TempDir
import java.io.InputStream
import java.lang.instrument.ClassFileTransformer
import java.lang.instrument.Instrumentation
import java.lang.module.ModuleDescriptor
import java.lang.module.ModuleFinder
import java.lang.module.ModuleReader
import java.lang.module.ModuleReference
import java.net.URI
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.security.ProtectionDomain
import java.util.Optional
import java.util.stream.Stream
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins that a class in an explicit named module, defined after the agent is installed, loads, runs
 * and counts when woven. The holder sits in the boot loader's unnamed module, which a named module
 * does not read. HotSpot adds that edge for a class an agent changed, but a second class of the
 * same module can resolve a dynamic constant before the JVM's edge lands and fail for good, so the
 * agent adds the edge itself before it hands back the woven bytes (ADR 0004). Both probe-array
 * forms are covered, since they reach the holder at different instructions. Also pins that a class
 * whose edge cannot be added is skipped with no probes registered, and that the agent adds no edge
 * for a class it does not weave.
 */
class NamedModuleWeavingTest {
    private val instrumentation: Instrumentation = ByteBuddyAgent.install()
    private var otherlode: OtherlodeInstrumentation? = null
    private var transformer: ResettableClassFileTransformer? = null
    private var installedOn: Instrumentation = instrumentation
    private val resource = ResourceAttributes("test", null, "instance-1", null, "run-1")
    private val holderModule: Module by lazy { Class.forName(BootstrapHolder.HOLDER_CLASS_NAME, false, null).module }

    @TempDir
    lateinit var directory: Path

    private var probe: ClassFileTransformer? = null

    @AfterTest
    fun tearDown() {
        probe?.let { instrumentation.removeTransformer(it) }
        transformer?.let { otherlode?.uninstall(installedOn, it) }
    }

    private fun install(
        registry: ProbeRegistry,
        includePackages: String,
        on: Instrumentation = instrumentation,
    ) {
        val agent = OtherlodeInstrumentation(AgentConfig.parse("includePackages=$includePackages"), registry)
        otherlode = agent
        installedOn = on
        transformer = agent.install(on)
    }

    /** Defines [fixture] in an explicit named module of a new layer, and returns that module before any of its classes loads. */
    private fun defineNamedModule(fixture: VersionedFixtures.Fixture): Module {
        val packageName = fixture.name.substringBeforeLast('.')
        val moduleName = "otherlode.fixture." + packageName.substringAfterLast('.')
        val classFile = directory.resolve(fixture.internalName + ".class")
        classFile.parent.createDirectories()
        classFile.writeBytes(fixture.bytes)
        val reference = DirectoryModuleReference(ModuleDescriptor.newModule(moduleName).exports(packageName).build(), directory)
        val finder =
            object : ModuleFinder {
                override fun find(name: String): Optional<ModuleReference> = Optional.ofNullable(reference.takeIf { name == moduleName })

                override fun findAll(): Set<ModuleReference> = setOf(reference)
            }
        val boot = ModuleLayer.boot()
        val configuration = boot.configuration().resolve(finder, ModuleFinder.of(), setOf(moduleName))
        return boot.defineModulesWithOneLoader(configuration, javaClass.classLoader).findModule(moduleName).get()
    }

    private class DirectoryModuleReference(
        descriptor: ModuleDescriptor,
        private val directory: Path,
    ) : ModuleReference(descriptor, directory.toUri()) {
        override fun open(): ModuleReader =
            object : ModuleReader {
                private fun file(name: String): Path? = directory.resolve(name).takeIf { Files.isRegularFile(it) }

                override fun find(name: String): Optional<URI> = Optional.ofNullable(file(name)?.toUri())

                override fun open(name: String): Optional<InputStream> = Optional.ofNullable(file(name)?.let { Files.newInputStream(it) })

                override fun read(name: String): Optional<ByteBuffer> =
                    Optional.ofNullable(file(name)?.let { ByteBuffer.wrap(Files.readAllBytes(it)) })

                override fun list(): Stream<String> = Stream.empty()

                override fun close() {}
            }
    }

    /**
     * Records whether the fixture's module reads the holder, from a transformer registered after the
     * agent's, so before the JVM adds its own edge for a class that any agent, JaCoCo included, changed.
     */
    private fun recordReadsAtLoadHook(fixture: VersionedFixtures.Fixture): List<Boolean> {
        val reads = mutableListOf<Boolean>()
        val later =
            object : ClassFileTransformer {
                override fun transform(
                    module: Module?,
                    loader: ClassLoader?,
                    className: String?,
                    classBeingRedefined: Class<*>?,
                    protectionDomain: ProtectionDomain?,
                    classfileBuffer: ByteArray?,
                ): ByteArray? {
                    if (className == fixture.internalName && module != null) reads += module.canRead(holderModule)
                    return null
                }
            }
        probe = later
        instrumentation.addTransformer(later, true)
        return reads
    }

    private fun hitsOf(
        registry: ProbeRegistry,
        className: String,
        methodName: String,
    ): List<Long> {
        val probes =
            registry
                .manifest(resource)
                .probes
                .filter { it.className == className && it.methodName == methodName && it.kind == ProbeKind.METHOD }
        val hits =
            registry
                .computeDeltaBatch(resource)
                .batch.deltas
                .associate { (it.classId to it.probeIndex) to it.hitsTotal }
        return probes.sortedBy { it.probeIndex }.map { hits[it.classId to it.probeIndex] ?: 0L }
    }

    private fun assertRunsAndCounts(fixture: VersionedFixtures.Fixture) {
        val registry = ProbeRegistry()
        install(registry, VersionedFixtures.PACKAGE)
        val module = defineNamedModule(fixture)
        assertFalse(module.canRead(holderModule), "the fixture's module starts without a read edge to the holder")

        val type = Class.forName(fixture.name, true, module.classLoader)
        val target = type.getDeclaredConstructor().newInstance()

        assertEquals(module, type.module)
        assertEquals(1, type.getMethod("pick", Int::class.java).invoke(target, 3))
        assertEquals(listOf(1L), hitsOf(registry, fixture.name, "pick"))
        assertEquals(listOf(1L), hitsOf(registry, fixture.name, "<clinit>"))
        assertTrue(module.canRead(holderModule))
    }

    @Test
    fun `a class of version 55 in a named module loads, runs and counts through the dynamic constant`() {
        assertRunsAndCounts(VersionedFixtures.plain(55))
    }

    @Test
    fun `a class below version 55 in a named module loads, runs and counts through the accessor`() {
        assertRunsAndCounts(VersionedFixtures.plain(52))
    }

    @Test
    fun `a class in a named module that the include rules do not match runs unwoven and the agent adds no read edge`() {
        val registry = ProbeRegistry()
        val fixture = VersionedFixtures.plain(55)
        install(registry, "com.example.unrelated")
        val readsAtLoadHook = recordReadsAtLoadHook(fixture)
        val module = defineNamedModule(fixture)

        val type = Class.forName(fixture.name, true, module.classLoader)
        val target = type.getDeclaredConstructor().newInstance()

        assertEquals(module, type.module)
        assertEquals(1, type.getMethod("pick", Int::class.java).invoke(target, 3))
        assertEquals(emptyList(), hitsOf(registry, fixture.name, "pick"))
        assertEquals(listOf(false), readsAtLoadHook)
    }

    @Test
    fun `the agent adds the read edge before the JVM runs its own`() {
        val fixture = VersionedFixtures.plain(55)
        val registry = ProbeRegistry()
        install(registry, VersionedFixtures.PACKAGE)
        val readsAtLoadHook = recordReadsAtLoadHook(fixture)
        val module = defineNamedModule(fixture)

        Class.forName(fixture.name, true, module.classLoader)

        assertEquals(listOf(true), readsAtLoadHook)
    }

    @Test
    fun `a class whose read edge cannot be added runs unwoven, is skipped and registers no probes`() {
        val fixture = VersionedFixtures.plain(55)
        val registry = ProbeRegistry()
        val refusing =
            object : Instrumentation by instrumentation {
                override fun redefineModule(
                    module: Module,
                    extraReads: MutableSet<Module>,
                    extraExports: MutableMap<String, MutableSet<Module>>,
                    extraOpens: MutableMap<String, MutableSet<Module>>,
                    extraUses: MutableSet<Class<*>>,
                    extraProvides: MutableMap<Class<*>, MutableList<Class<*>>>,
                ): Unit = throw IllegalStateException("edge refused")
            }
        install(registry, VersionedFixtures.PACKAGE, refusing)
        val readsAtLoadHook = recordReadsAtLoadHook(fixture)
        val module = defineNamedModule(fixture)

        val type = Class.forName(fixture.name, true, module.classLoader)
        val target = type.getDeclaredConstructor().newInstance()

        assertEquals(1, type.getMethod("pick", Int::class.java).invoke(target, 3))
        val manifest = registry.manifest(resource)
        assertEquals(emptyList(), manifest.probes.filter { it.className == fixture.name })
        assertEquals(listOf(fixture.name), manifest.skippedClasses.map { it.className })
        assertTrue("edge refused" in manifest.skippedClasses.single().reason, manifest.skippedClasses.single().reason)
        assertEquals(listOf(false), readsAtLoadHook)
    }
}
