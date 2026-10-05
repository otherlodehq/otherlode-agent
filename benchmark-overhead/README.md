# Runtime overhead benchmark

Measures what the agent costs a real service: `spring-petclinic-rest` on Postgres, driven by k6,
with and without the agent. It is a standalone Gradle build, not part of the root build or its
`check`, and it needs Docker.

## What it measures

Each run starts a fresh Postgres, starts PetClinic in a container pinned to its own cores and limited to 2 GiB
(`-Xms1g -Xmx1g -XX:+UseG1GC`), warms it up until throughput is steady, then drives it with k6 for the measured window. The
agent exports to a real `otherlode-collector` container, logging only, so the flush's encoding and
send are the real ones and their cost is in the numbers.

Per run it records:

- **Startup:** milliseconds from just before the container is created to the first 200 from
  `/petclinic/actuator/health`, polled every 100 ms from the host, and Spring Boot's own "process
  running for" seconds. Every variant, `none` included, has the agent jar copied in, so the copy
  costs them all alike.
- **k6:** throughput and request count, `http_req_duration` average, p95 and p99,
  `iteration_duration` average and p95, and the checks pass rate. Each request carries a `name`
  tag and the `url` system tag is off, so a path with an id in it does not start a metric series
  of its own.
- **Per request:** CPU milliseconds, KiB allocated and GC pause per 1000 requests. In a closed loop
  a variant that serves more requests also allocates and collects more in total, and the
  container runs at its CPU limit in every variant, so these are the figures that compare
  variants; the totals are kept beside them.
- **JFR** (OpenTelemetry's `overhead.jfc` without `jdk.ThreadDump`, plus `jdk.ResidentSetSize` and
  `jdk.MetaspaceSummary`): JVM user CPU average and maximum, machine CPU average, GC pause total,
  G1 collection time, bytes allocated, heap used minimum and maximum, peak thread count, resident
  set size maximum, metaspace used, and network read and write rates. The recording starts with
  the window and is stopped as k6 finishes, so it holds neither the warmup nor the shutdown.
- **CPU:** the container's CPU seconds across the window, from two readings of its cgroup's
  `cpu.stat`, as average cores and per request. With no quota the throttling fields stay at zero and
  are kept only so a change that restores one shows. JFR's `jdk.CPULoad` shares are kept too, but
  they are taken against the host's CPU count, not PetClinic's pinned cores.
- **Manifests:** probes, endpoints and skipped classes, summed from the collector's log line for
  each manifest the run sent.
- **End of window:** `VmRSS` and `VmHWM` from `/proc/1/status`, and class space committed and used
  from `jcmd VM.metaspace`. Native Memory Tracking is left off, since it would slow every variant.

Allocation is each thread's last `jdk.ThreadAllocationStatistics` reading minus its first, summed.
The event is a per-thread running total, so adding up the readings would be wrong. Threads that ended
before the recording closed are not in it, so the figure is a lower bound for all variants alike.

The window measures the agent in steady state. Its first flush, at a random point inside the first
interval, falls in the warmup, so the first full manifest and the static baseline are sent before
measurement begins and their cost shows in startup and warmup, not in the window. Each flush in the
window walks the loaded classes to count dependency classes; the forward check for unreported
classes runs only on every tenth flush, about ten minutes in, so no window holds one and only the
shutdown flush, after the window, runs it. The cgroup and JFR readings also span k6's container
starting and stopping, about a second either side of the load, the same for every variant.

## Warmup

The warmup runs the measured window's k6 script and users in slices of `warmupSliceSeconds`, reading each
slice's request count from its k6 summary and printing the slice's throughput. It ends when two
consecutive slices each differ from the slice before them by at most `warmupSteadyDrift`, once at
least `warmupMinSeconds` have passed, or at `warmupSeconds`. The minimum keeps an early flat patch in a
slowly rising curve from ending it. With PetClinic pinned to one core the compiler threads share that
core with the application, so how long warming takes varies by config and by run.

Reaching the cap is not a failure by itself: the window's own rule (below) still judges the measured
window. Each run records `warmupSecondsUsed`, `warmupSteady` (1 when the rule ended it, 0 at the cap)
and `warmupLastSliceThroughputPerSec`, and the summary shows the mean and range of `warmupSecondsUsed`
per variant. The raw k6 output of each slice is kept as `warmup-slice-<n>.json` in the run's directory.

## Core pinning

PetClinic is pinned to dedicated cores with a cpuset (`HostConfig.withCpusetCpus`), not limited by a
CFS quota, and no other container may use those cores. A quota let k6, Postgres, dockerd and the
Gradle JVM compete with PetClinic for the same cores, so a closed loop measured the client. The split
follows Docker's CPU count `n` (`docker info`, the VM's count under Docker Desktop):

- PetClinic: `max(1, n / 4)` cores, at most 2, from core 0.
- Postgres and the collector, sharing one set: `max(1, n / 4)` cores, the highest-numbered.
- k6: every core left over, in between.

| `n` | PetClinic | k6 | Postgres and collector |
|---|---|---|---|
| 4 | `0` | `1-2` | `3` |
| 8 | `0-1` | `2-5` | `6-7` |
| 12 | `0-1` | `2-8` | `9-11` |
| 16 | `0-1` | `2-11` | `12-15` |

k6 runs 4 virtual users per PetClinic core, closed loop, so PetClinic's cores stay busy; capping
PetClinic at 2 cores keeps a run on a large host comparable with one on a 4-CPU runner. With 2 CPUs PetClinic keeps core 0 and k6, Postgres and the collector share core 1. With 1 CPU the
split cannot keep PetClinic's core to itself and the harness stops. The memory limit stays at 2 GiB.
The chosen sets are written to `metadata.properties` as `cpuset.petclinic`, `cpuset.k6` and
`cpuset.postgresAndCollector`. The Gradle JVM and dockerd are not pinned; on a 4-CPU runner they
share the cores of the other containers.

## The matrix

Two configs, one per Gradle invocation (`-Pconfig=headline|ceiling`):

| Config | `includePackages` |
|---|---|
| `headline` | `org.springframework.samples.petclinic` |
| `ceiling` | `org.springframework` |

Three variants of each: `none` (no agent), `agent` (default options), and `agent-baseline`
(`staticBaselineEnabled=true`). Every agent run uses `exportUrl=http://collector:4319` and leaves
`flushIntervalSeconds` at its default of 60, so three flushes fall in the default 180 s window.

`-Prepeats=N` (default 6) repeats the whole set. In round `r` the variants run in the order rotated
by `r`, so no variant always goes first.

## Run it locally

```
./gradlew shadowJar
git clone https://github.com/otherlodehq/otherlode-collector /path/to/otherlode-collector
./gradlew -p benchmark-overhead test \
  -PagentJar=$PWD/build/libs/otherlode-agent-0.1.0-SNAPSHOT.jar \
  -PcollectorDir=/path/to/otherlode-collector \
  -Pconfig=headline
```

| Property | Default | Meaning |
|---|---|---|
| `-PagentJar` | required | The shaded agent jar from the root `shadowJar`. |
| `-PcollectorDir` | required | A local clone of `otherlode-collector`; its `Dockerfile` is built. |
| `-Pconfig` | `headline` | `headline` or `ceiling`. |
| `-Prepeats` | `6` | Runs per variant. A multiple of three gives each variant each position equally often. |
| `-PwarmupSeconds` | `600` | The cap on the warmup load before the measured window. |
| `-PwarmupSliceSeconds` | `30` | Length of each warmup slice. |
| `-PwarmupMinSeconds` | `90` | The least warmup before steadiness may end it. |
| `-PwarmupSteadyDrift` | `0.03` | How far a slice's throughput may differ from the slice before it, as a fraction, and still count as steady. |
| `-PwindowSeconds` | `180` | Length of the measured window. |

Give both paths absolute, as above, or relative to `benchmark-overhead/`: `-p` makes that Gradle's
start directory, so a path relative to the repository root names nothing. The harness stops if
either is missing.

Without `-PagentJar` and `-PcollectorDir`, `test` runs only the unit tests of the harness's own
parsing and summary code. With just one of them, the build stops with a message.

The first run builds the PetClinic image (a clone and a Maven build); later invocations reuse
Docker's layer cache. The build pins PetClinic to commit `afc8fc1d3b8ec8de69414482b3bd70a1da78cb95`.

## Run it in CI

The `benchmark-overhead` workflow is manual (`workflow_dispatch`). It takes `collector_ref`
(default `master`) and `repeats` (default 6), runs both configs in parallel on `ubuntu-latest`, and
uploads `benchmark-overhead/build/results` as an artifact even when a run fails. Each config's
`summary.md` is added to the job summary.

## What makes a run invalid

An invalid run fails the build and stops the matrix. The results written so far, and the failed run's
log, stay in the results directory.

- PetClinic exits early, never logs its "Started PetClinicApplication" line, or its log contains
  `VerifyError`, in any variant.
- The k6 checks pass rate is below 1.0. The script checks every response's status.
- PetClinic was not saturated: its container averaged under 90% of its pinned cores over the window
  (`cpuCoresAvg`), so the closed loop was limited by k6 or Postgres and its throughput would not
  describe PetClinic. CPU per request is still valid in such a run, but the run fails regardless.
- The window was not steady: its last third served more than 10% more requests than its first,
  so the JIT was still compiling when measurement began (raise `-PwarmupSeconds` if the warmup hit its cap; lower `-PwarmupSteadyDrift` or lengthen `-PwarmupSliceSeconds` if it was judged steady), or more than
  10% fewer, so something degraded through it.
- For an agent variant: the agent logged an error (`SEVERE: otherlode:` before Spring Boot sets up
  logging, a line at `ERROR` naming `otherlode:` after); the collector's `/metrics`
  did not show `otherlode_collector_ingest_accepted_total` rising for deltas and for the manifest
  (and the static baseline, for `agent-baseline`), or showed `otherlode_collector_ingest_rejected_total`
  rising; or the run's manifests carried no probe or no endpoint, or a disabled endpoint module.
  Delta batches go out even when empty, as a heartbeat, so accepted deltas alone would not show
  that anything was instrumented.

For every agent run the harness also counts the classes the agent skipped, from the manifests, and
names those it logged an `instrumentation failed for <class>` warning for. They are published
beside the numbers and are not a failure. The summary marks with
`*` a change whose median lies inside `none`'s own minimum to maximum.

## Results

Under `benchmark-overhead/build/results/<config>/`:

- `runs.csv`: one row per run with every metric.
- `summary.md`: per variant, each metric's median with its minimum and maximum, and the median's
  change against `none`; then the skipped classes.
- `metadata.properties`: the runner's OS and CPU count, the PetClinic image's `java -version`, the
  agent jar's SHA-256 and modification time, the agent repository's commit and whether its tree was
  dirty (a local jar can predate the commit), the PetClinic commit, the collector
  directory's commit, resolved image digests, and the repeats, window and warmup settings used.
- `runs/<variant>-r<N>/`: the raw k6 JSON and log, the JFR file, PetClinic's log, and the
  collector's log lines from the run.

## Attribution

The harness is modelled on OpenTelemetry Java instrumentation's
[`benchmark-overhead`](https://github.com/open-telemetry/opentelemetry-java-instrumentation/tree/main/benchmark-overhead)
(Copyright The OpenTelemetry Authors, Apache-2.0): the container layout, the closed-loop k6 run,
the warmup with a throwaway JFR recording, and `src/main/resources/overhead.jfc`, which is their
file with `jdk.ThreadDump` removed and `jdk.ResidentSetSize` and `jdk.MetaspaceSummary` enabled.
Those files carry their copyright and licence notice and say they were modified. The k6 script is
written for the current PetClinic API and does not use their data.
