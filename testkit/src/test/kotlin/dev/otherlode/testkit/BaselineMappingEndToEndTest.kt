package dev.otherlode.testkit

import dev.otherlode.export.DeclaredClass
import dev.otherlode.export.DeclaredMethod
import dev.otherlode.export.DeltaBatch
import dev.otherlode.export.DependencyDelta
import dev.otherlode.export.DependencyDiscoverySource
import dev.otherlode.export.DependencyIdentity
import dev.otherlode.export.DependencyIdentitySource
import dev.otherlode.export.DependencyLocation
import dev.otherlode.export.HttpExporter
import dev.otherlode.export.ResourceAttributes
import dev.otherlode.instrumentation.staticscan.BaselineReferenceFilter
import dev.otherlode.instrumentation.staticscan.StaticBaselineChunker
import dev.otherlode.instrumentation.staticscan.StaticScanResult
import dev.otherlode.registry.DependencyRegistry
import dev.otherlode.registry.ExternalClassRegistry
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Drives the agent's own filter and chunker into a collector that has seen no manifest mapping, to
 * pin that a complete baseline alone is enough to judge a dependency.
 */
class BaselineMappingEndToEndTest {
    private val collector = OtherlodeTestCollector.start()
    private val exporter = HttpExporter(collector.exportUrl)
    private val resource = ResourceAttributes("svc", null, "i-1", null, "run-1")

    @AfterTest
    fun tearDown() {
        collector.close()
    }

    @Test
    fun `a dependency only a never-loaded class references reads unreached once the baseline arrives, before any manifest maps its classes`() {
        val dependencies = DependencyRegistry(indexClassNames = true)
        val id =
            dependencies.register(
                listOf(DependencyIdentity("org.example", "lib", "1.0")),
                DependencyIdentitySource.POM_PROPERTIES,
                "/libs/lib.jar",
                DependencyDiscoverySource.STARTUP_CLASSPATH,
                classNames = listOf("org.example.Old"),
            )
        dependencies.markListingComplete()
        val externalClasses = ExternalClassRegistry(dependencies::isListingComplete, { null })
        exporter.exportManifest(
            watchedManifest(
                resource = resource,
                probes = emptyList(),
                dependencies =
                    listOf(
                        DependencyLocation(
                            dependencyId = id,
                            identities = listOf(DependencyIdentity("org.example", "lib", "1.0")),
                            identitySource = DependencyIdentitySource.POM_PROPERTIES,
                            location = "/libs/lib.jar",
                            discoverySource = DependencyDiscoverySource.STARTUP_CLASSPATH,
                            classCount = 10,
                        ),
                    ),
                referencesRecorded = true,
                dependenciesListed = true,
            ),
        )
        exporter.exportDeltaBatch(DeltaBatch(resource, emptyList(), dependencyDeltas = listOf(DependencyDelta(id, 1L, 1L))))
        val scan =
            StaticScanResult(
                declaredClasses =
                    listOf(
                        DeclaredClass(
                            "demo.Legacy",
                            listOf(DeclaredMethod("apply", "()V", referencedClasses = listOf("org.example.Old"))),
                        ),
                    ),
                unreadableClasses = emptyList(),
            )

        val filtered = BaselineReferenceFilter(dependencies, externalClasses).filter(scan)
        StaticBaselineChunker.chunk(filtered, resource, scannedAt = 1000L, maxEntriesPerChunk = 100).forEach(exporter::exportStaticBaseline)

        assertEquals(listOf("org.example:lib"), collector.unreachedDependencies().map { it.identityKey })
        assertEquals(emptyList(), collector.unreferencedDependencies())
    }
}
