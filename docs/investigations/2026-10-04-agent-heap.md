# The agent's heap and native memory on PetClinic, 2026-10-04

Evidence for `STATUS.md`'s entry "The agent's heap after delivery: measured, to grill". Measured against agent commit `3707e98` (the jar built at 14:15 that day) by an Opus research agent; findings, not fixes. The scripts are beside this file in `heap/`; the heap dumps are not kept (several hundred MB each) and `heap/measure.sh` with `DUMP=1` makes new ones.

## Question

The overhead harness's ceiling config (`includePackages=org.springframework`) showed peak used heap 662 MiB without the agent and 810 MiB with it, about 1.5 KB a probe, and RSS rose about 90 MB even with the headline config (`includePackages=org.springframework.samples.petclinic`, 1,489 probes). What holds the memory, and does it stay after the manifest is delivered?

## Method

- spring-petclinic-rest at `afc8fc1d3b` (Spring Boot 4.1.1), its fat jar run on Corretto 21.0.5 with `-Xmx1g -XX:+UseG1GC -XX:NativeMemoryTracking=summary`, the agent with `flushIntervalSeconds=5`, Postgres 16.3 in Docker.
- Two measurement points per config, each after two `jcmd GC.run`, so the class histogram is live objects only:
  - **undelivered**: after startup and about 330 requests, with `exportUrl` pointing at a port nothing listens on. A point before the first flush is impossible at a 5 s interval, since the ceiling config takes 18 s to start.
  - **delivered**: about 60 s after an `otherlode-collector` container came up on that port (the ceiling run's collector logged 79 manifest and 15 delta posts).
- `jcmd GC.class_histogram`, `GC.heap_info`, `VM.native_memory summary` and RSS at each point; heap dumps of the delivered points read with Eclipse MAT 1.16.1 (headless retained-set and OQL queries).
- Reproduce: `heap/measure.sh <label> <includePackages|none> <port>` for `none`, `org.springframework.samples.petclinic` and `org.springframework`, then `heap/histogram-diff.py` and `heap/nmt-diff.py` over the outputs. The script's header lists what it needs.

## Totals

| | none | headline (1,489 probes) | ceiling (95,760 probes, 4,090 classes) |
|---|---|---|---|
| Live heap, undelivered (MB) | 41.2 | 50.3 | 167.2 |
| Live heap, delivered (MB) | 41.2 | 51.3 (+9.6) | 155.9 (+114.7, about 1.2 KB a probe) |
| RSS, delivered (MB) | 451 | 529 (+78) | 794 (+343) |
| NMT committed (MB) | 490 | 569 | 801 |

Delivery frees 10.8 MB in the ceiling: the in-flight wire objects, `ProbeLocation` (9.7 MB), `ProbeDelta` (1.2 MB), `ClassLocation` (0.16 MB). Nothing else. The harness's 662 to 810 MiB figure was peak used heap, garbage included; the live growth is about 115 MB and the rest is transform-time garbage.

## What holds the ceiling's live heap after delivery

1. **`ProbeRegistry`, 62.2 MB retained.** `ClassEntry.probes` (`List<ProbeMeta>`) is 55.3 MB, about 578 B a probe: METHOD probes about 1.3 KB each, BRANCH probes about 160 B.
   - Strings: 341k objects, 25.9 MB, of which 142k distinct values; deduplication would save 14.9 MB. Method names are 40k objects for 13k distinct values, descriptors 42k for 11k.
   - Lists: 222k `ArrayList` and `Object[]`, 13.3 MB; 38.5k empty, 118k holding one element, 3.2 MB of unused slots.
   - `ProbeMeta` itself: 96 B a shell (23 fields), 9.2 MB.
   - Call edges (`CallEdge`) 3.1 MB; branch detail (`BranchOutcome` 3.0, `LineRange` 1.2, `BranchSite` 1.2, `ConditionPart` 0.8 MB).
   - The counting arrays (`counts`, `lastSent`, `firstSeenAt`) are 2.4 MB, 24 B a probe, plus `decreaseWarned` 0.17 MB.
   - After `advanceManifestBaseline` (`ProbeRegistry.kt:790-793`), which only sets `manifestIncluded`, the one remaining read of a delivered `ProbeMeta` is `entry.probes[index].kind` in `changedProbesOf` (`ProbeRegistry.kt:491`); the other loops skip included entries (`:718`, `:763`), and the full `manifest()` (`:566`) is documented as test-only. `ClassEntry`'s `superClassName`, `interfaceNames`, `classReferences` and `sourceFile` (`ProbeRegistry.kt:97-110`, about 1.2 MB) feed only the send-once `ClassLocation` and `ClassReferences`.
2. **`OtherlodeInstrumentation.tableCaches`, 34.0 MB retained.** One `CrossClassTableCache` per loader (`OtherlodeInstrumentation.kt:150-163`, a 2048-entry LRU at `:1471`, the class at `BranchSiteAnalyzer.kt:283-338`). The fat-jar loader's cache is 32.7 MB, a second 1.35 MB: 1,827 `MethodTable`s (`BranchSiteAnalyzer.kt:4442`) at about 18 KB each, mostly maps keyed by `Pair<String,String>` (220k `LinkedHashMap$Entry`, 91k `Pair`, 49.6k `RawCandidate`, 168k strings of 31k distinct values; deduplication would save 9.7 MB). The LRU bound is never reached; the cache is held weakly by a loader that lives as long as the process, and is read only at transform time (`:1226`). Headline: 0.68 MB.
3. **`WovenClasses`, 4.5 MB**, about 1.1 KB a class (`WovenClasses.kt:273`): the stored plans ADR 0053 keeps for a re-weave, by design.
4. **Smaller:** `DependencyRegistry` 1.25 MB (headline 1.96 MB), almost all `loadedClassNames` (`DependencyRegistry.kt:103-104`, 13k strings), by design for counting distinct classes; ByteBuddy `TypePool` caches in `AdviceBinder` and `ModuleSupport`, about 0.8 MB; the JDK HTTP client initialising TLS for a plain `http` URL (cacerts `X509CertImpl` 0.88 MB, `PlainHttpConnection` 0.8 to 1.6 MB).

Items 1 to 3 and `DependencyRegistry` account for about 102 of the 115 MB.

## Headline config, +9.6 MB live

`DependencyRegistry` 1.96, `ProbeRegistry` 1.0, `tableCaches` 0.68, TLS certificates 0.88, HTTP connections 0.77, `InstrumentationImpl` 0.4 MB; the rest is class objects and `MethodType`/`LambdaForm` churn from about 3.5k extra classes (in the headline dump: ByteBuddy 1,494, the agent about 660, shaded Kotlin 190, the HTTP client 302, TLS about 460).

## Native memory, delivered, against no agent

| Area | Headline | Ceiling | Notes |
|---|---|---|---|
| Java heap committed | +23 MB | +195 MB | The ceiling ran 217 GC pauses against 28: transform-time garbage grows G1, which keeps 329 MB committed for 156 MB live. The largest RSS item in the ceiling. |
| Metaspace | +16.6 MB | +22.4 MB | |
| Code cache | +13.5 MB | +17.6 MB | |
| Thread | +12 MB | +14 MB | 7 HTTP client threads and 3 `otherlode-export` threads; NMT counts a full 2 MB stack each on macOS, so real RSS is lower (inferred). |
| Symbol | +7.3 MB | +8.6 MB | |
| Internal | +0.8 MB | +19.1 MB | The JVM's off-heap copy of each woven class's bytes, kept because the transformers are retransformation-capable (ADR 0053); inferred, and the woven classes' class files sum to 18.2 MB. By design. |
| Compiler | 0 | +22.5 MB | C2 arena, likely transient (inferred). |

## Candidate changes, ranked by what they would free

1. **Drop delivered `ProbeMeta`.** On `advanceManifestBaseline`, replace `ClassEntry.probes` with a compact kind array and clear the class-location fields, keeping everything for classes still unconfirmed or unsent; `manifest()` would need a test-only path. About 55 MB in the ceiling, 0.9 MB in the headline. The decision inside it: the manifest becomes permanently send-once, so it could never be re-sent to a collector that lost its state, which matches what the agent does in practice.
2. **Release the table caches after startup**: clear them once transforms go quiet (say, N flushes with no transform), hold the values through `SoftReference`, or scope them to a burst of transforms. About 34 MB; a later lazy load or a retransformation re-parses.
3. **Intern names and size lists** when building `ProbeMeta`, `CallEdge`, `MethodTable` and `WeavePlan` (`emptyList()`, `listOf(x)`, `trimToSize`). 15 to 25 MB before delivery, and smaller plans after.
4. **Allocate less while weaving**, so G1 does not grow to about 190 MB more committed heap; an adopter can work around it with `-XX:G1PeriodicGCInterval`.
5. **Defer the HTTP client's TLS set-up** for an `http` export URL: about 1 MB and 460 classes.

Not avoidable without changing a decision: the counting arrays, the stored plans and the JVM's copy of woven class bytes (ADR 0053), and metaspace and code cache for ByteBuddy and the HTTP client.
