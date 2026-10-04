# What the agent adds to PetClinic's startup, 2026-10-04

Evidence for `STATUS.md`'s entry "The agent's startup: measured, to grill", which is grilled with the heap entry beside it. Measured against the agent jar built from `3707e98` by an Opus research agent; findings, not fixes. Scripts are beside this file in `startup/`; the profiles are not kept, and the scripts make new ones.

## Question

The overhead harness showed PetClinic REST's "process running for" rise from about 3.7 s to 6.4 s with the agent on the headline config (`includePackages=org.springframework.samples.petclinic`, about 100 classes woven), and by about 13 s on the ceiling (`includePackages=org.springframework`, 4150 classes). Where does the time go, and how much of it is avoidable?

## Method

- spring-petclinic-rest at `afc8fc1d3b` (Spring Boot 4.1.1), its fat jar run on the host, a 12-core Mac, against Postgres 16.3 in Docker; the agent's export URL dead (`http://127.0.0.1:1`), which a first flush at a random point in its 60 s interval makes irrelevant to startup.
- Clean timings on Corretto 21.0.5, which ships a CDS archive (the local Temurin 21.0.4 has none, so `-Xshare:on` fails there), five to seven runs a configuration, medians. The machine's load average was 3 to 6 from another build, which the medians absorb but do not remove.
- Profiles from async-profiler 4.1, wall and CPU at 1 ms, with invocation counting on the class-file locators; JFR's `profile` settings gave too few samples to use.
- Reproduce: `startup/time-startup.sh` per configuration, and the two `profile-*.py` scripts over a collapsed async-profiler wall profile.

## Timings, "process running for", Corretto

| Configuration | s | Over no agent |
|---|---|---|
| no agent | 3.21 to 3.26 | |
| `-Xshare:off`, no agent | 3.30 | +0.04 |
| a no-op `-javaagent` | 3.31 | +0.05 |
| an agent that only appends an empty jar to the bootstrap path, printing the CDS warning | 3.33 | +0.07 |
| otherlode, `includePackages=zz.nothing`, `endpointsEnabled=false` | 3.62 | +0.36 |
| otherlode, `includePackages=zz.nothing`, endpoints on | 5.07 | +1.86 |
| headline, endpoints off | 4.09 | +0.85 |
| headline, the agent jar without the JAX-RS module | 3.97 | +0.76 |
| **headline** | **5.47** | **+2.26** |
| ceiling, endpoints off | 13.80 | +10.6 |
| **ceiling** | **15.09** | **+11.9** |

Temurin runs agreed: about +2.2 s headline, about +13 s ceiling.

## The headline's +2.26 s

1. **The JAX-RS endpoint module's type matcher, about 1.5 s, measured by removing the module.** It runs on every class the JVM loads, 19.6k of them (13.2k from nested jars), whatever `includePackages` says: the endpoint pipeline has no scope filter and does not ignore bootstrap classes (`EndpointInstrumentation.kt:128`). `JaxRsModule.kt:100-103` asks `isInterface`/`isAbstract` first, which parses every class (about 134 ms), then `hasInheritedPathOrVerb` (`JaxRsModule.kt:343`) walks each class's supertypes, reading each one's class file through the loader. ByteBuddy's default pool strategy gives each transform a fresh `TypePool.CacheProvider.Simple` (javap of the shaded `AgentBuilder$PoolStrategy$Default.typePool`), so nothing is shared between classes: about 25.9k class-file reads during startup. PetClinic has no JAX-RS on its classpath.
2. **Weaving the ~100 PetClinic classes, about 0.45 to 0.5 s** (4.09 less 3.62): `instrument()` 212 ms, ByteBuddy's rebase and `make` about 200 ms, the method tier's type matcher (`isSafeToInstrument` and `unsafeAnnotation`, `TypeMatchPolicy.kt:550`) 70 ms; about 4 to 5 ms a class, much of it ByteBuddy and ASM running cold, and supertype reads for Spring Data repository hierarchies with no shared cache.
3. **`premain`, about 0.40 s** (the launcher class loads at 0.455 s against 0.039 s without the agent): building the transformer, mostly loading and initialising ByteBuddy, 134 ms; the `HttpClient` built eagerly (`HttpOtlpStyleExporter.kt:38`) 115 ms; `AgentConfig.parse` and its `<clinit>` about 40 ms; retransforming `InnerClassLambdaMetafactory` (`LambdaFactoryHook.install`) 28 ms; logging set-up 22 ms.
4. **Classes outside scope cost about nothing**: nothing in scope with endpoints off adds 0.36 s, which is `premain`. The name pre-filter in `TypeMatchPolicy.typeNameMatcher`, `ClassBytesCapture` (4 ms) and ByteBuddy's lazy describe are cheap.
5. **The other endpoint modules cost about nothing**: their matchers are `namedOneOf`, and the headline without JAX-RS (3.97) is within noise of the headline with endpoints off (4.09).
6. **Losing CDS costs about nothing** (0.07 s at most): almost all of startup is Spring's classes from nested jars, which the default archive does not cover.
7. **Background work blocks nothing but costs CPU.** CPU across all threads went from 12.3 s to 20.6 s (Temurin): the main thread +2.1 s, the JIT compiler threads +4.7 s, the dependency listing thread 0.93 s (`JarContents.of` streaming nested jars), GC +0.26 s. No lock waits on the main thread. On 12 cores that barely moves wall time; in a container of one or two CPUs much of it would land on startup, which may be why the harness's Docker figures (3.7 to 6.4 s) are worse than these (inferred).

## The ceiling's +11.9 s

It scales with the classes woven, about 3 ms each across 4150. Main-thread wall in the transformer (Temurin profile):

| What | ms | Notes |
|---|---|---|
| ByteBuddy's rebase, `MethodRegistry.prepare` | 4,540 | Builds the method graph, checks every inherited method's parameter and return types for visibility, and validates: all through the type pool. |
| `instrument()` | 3,720 | `analyzeBytecode` 2,060 (900 of it `crossClassLookup` class-file reads, `OtherlodeInstrumentation.kt:1271`), `getKeptSites` 490, `BranchKeys.digest` 430, re-reading the class file (ADR 0052) 400, `weave` 350. |
| ByteBuddy's `TypeWriter.make` | 1,280 | The ASM read and write; inherent to rebasing. |
| The JAX-RS matcher | 1,300 | As in the headline. |
| `ByteBuddy.rebase`'s builder | 510 | |
| The method tier's matcher | 240 | |
| `premain` | 410 | |

- About 111k class-file reads in all: the rebase's `prepare` about 53k, JAX-RS about 26k, the analyser's cross-class lookups about 11k, describing and matching about 8.8k, the rebase builder about 6.6k, each class's own class file about 4.6k.
- Locating class files is 47% of transform time (5.6 of 11.8 s) and parsing them another 9%; in the headline, 55% and 16%.
- A read costs about 40 to 50 µs, 57% of it in Spring Boot's `JarUrlClassLoader.findResource` and `URLClassLoader.findResource` scanning 107 nested jars after the boot and platform loaders, most of the rest inflating the entry. The medium is slow; the count of reads is the problem.
- `BranchKeys.digest` (`branch/BranchKeys.kt:146-165`) formats each of 16 bytes with `"%02x".format` and creates a `MessageDigest` per call: 322 of its 430 ms is the formatting.

## Inherent and avoidable

Inherent: ByteBuddy's rebase and ASM's rewrite of each woven class, the one class-file read per woven class the analysis needs (ADR 0052), loading ByteBuddy in `premain`, and some JIT work.

Avoidable, by payoff:

1. **Gate the JAX-RS module per loader** before any parsing, with a cached `loader.getResource("jakarta/ws/rs/Path.class")` (and the `javax` name), as OpenTelemetry's `hasClassesNamed` does; put a cheap name check ahead of the modifier checks; cache the supertype answer per loader and name. About 1.5 s in every run, whatever the include rules.
2. **A type pool shared across transforms, per loader** (ByteBuddy's `PoolStrategy.WithTypePoolCache`, or a pool like OpenTelemetry's `AgentCachingPoolStrategy`), used also by `describeClassFile` (`OtherlodeInstrumentation.kt:1218`) and `crossClassLookup`, so the repeat reads go; optionally a per-loader cache of class-file bytes in front of `ClassFileLocator.ForClassLoader`. Several seconds at the ceiling and part of the headline's 0.45 s, inferred: no changed agent was built. Its memory has to be weighed against the heap findings, where a transform-time cache held for the process's life is 34 MB of the ceiling's retained heap (`2026-10-04-agent-heap.md`).
3. **`BranchKeys.digest`** with `HexFormat` or a lookup table: about 0.3 s at the ceiling.
4. **Build the `HttpClient` lazily** on the export thread: about 0.1 s.
5. **The dependency listing's 0.9 s of CPU**, which matters on hosts with one or two CPUs.
