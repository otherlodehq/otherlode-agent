package dev.otherlode.testkit;

import dev.otherlode.testkit.junit5.OtherlodeExtension;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;

/**
 * Calls every public query of {@link OtherlodeTestCollector} from Java, with and without the
 * optional arguments. It is compiled and never run.
 */
final class JavaApiCompileCheck {
    private JavaApiCompileCheck() {}

    @SuppressWarnings("unused")
    static void callEveryQuery() throws TimeoutException {
        try (OtherlodeTestCollector collector = OtherlodeTestCollector.start()) {
            OtherlodeTestCollector onPort = OtherlodeTestCollector.start(0);
            String url = collector.getExportUrl();
            Duration timeout = Duration.ofSeconds(10);

            collector.awaitNextFlush(timeout);
            collector.awaitSettled(timeout);
            collector.awaitProbe("com.acme.A", "m", timeout);
            collector.awaitEndpoint("GET", "/a", timeout);
            collector.awaitDependency(null, "lib", timeout);
            collector.awaitDependenciesListed(timeout);

            boolean hit = collector.wasHit("com.acme.A", "m") || collector.wasHit("com.acme.A", "m", "()V");
            long hits = collector.hitCount("com.acme.A", "m") + collector.hitCount("com.acme.A", "m", "()V");
            long omitted =
                    collector.omissionCount("com.acme.A", "m", 0)
                            + collector.omissionCount("com.acme.A", "m", 0, "()V")
                            + collector.omissionCount("com.acme.A", "m", "p")
                            + collector.omissionCount("com.acme.A", "m", "p", "()V");

            List<ProbeRef> neverHit = collector.neverHit();
            List<ProbeRef> routine = collector.neverHitRoutineOutcomes();
            List<ProbeRef> unread = collector.neverHitUnreadShapes();
            List<ClassFindingRef> uninitialized = collector.neverInitialized();
            List<ClassFindingRef> uninstantiated = collector.neverInstantiated();
            List<String> neverLoaded = collector.neverLoaded();
            List<UnreachedCluster> clusters = collector.unreachedClusters();
            List<OptionalParameterRef> never = collector.neverSupplied();
            List<OptionalParameterRef> always = collector.alwaysSupplied();
            List<SkippedClass> skipped = collector.skippedClasses();

            boolean called = collector.wasCalled("GET", "/a");
            long calls = collector.callCount("GET", "/a");
            List<EndpointRef> neverCalled = collector.neverCalled();
            List<EndpointRef> endpoints = collector.endpoints();
            List<DisabledEndpointModule> disabled = collector.disabledEndpointModules();

            DependencyStatus dependency = collector.dependency(null, "lib");
            List<DependencyStatus> unloaded = collector.unloadedDependencies();
            List<DependencyStatus> unreferenced = collector.unreferencedDependencies();
            List<DependencyStatus> unreached = collector.unreachedDependencies();
            List<AbsentReference> absent = collector.absentReferences();
            List<String> rejected = collector.rejectedPayloads();

            for (ProbeRef ref : neverHit) {
                ProbeKind kind = ref.getKind();
                GeneratedBy generatedBy = ref.getGeneratedBy();
                RoutineKind routineKind = ref.getRoutine();
                UnreadShape unreadShape = ref.getUnreadShape();
            }
            for (UnreachedCluster cluster : clusters) {
                RootKind rootKind = cluster.getRootKind();
                ClassFinding finding = cluster.getRootFinding();
            }
            for (EndpointRef endpoint : endpoints) {
                EndpointDiscoverySource source = endpoint.getDiscoverySource();
            }
            for (DependencyStatus status : unloaded) {
                DependencyUsage usage = status.getStatus();
                DependencyDiscoverySource source = status.getDiscoverySources().iterator().next();
            }
        }
    }

    @org.junit.jupiter.api.extension.ExtendWith(OtherlodeExtension.class)
    static class WithExtension {
        OtherlodeTestCollector shared() {
            return OtherlodeExtension.collector();
        }
    }
}
