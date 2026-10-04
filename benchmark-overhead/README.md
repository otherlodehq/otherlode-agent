# Runtime overhead benchmark

Measures what the agent costs a real service: `spring-petclinic-rest` on Postgres, driven by k6,
with and without the agent. It is a standalone Gradle build, not part of the root build or its
`check`, and it needs Docker.

## What it measures

Each run starts a fresh Postgres, starts PetClinic in a container limited to 2 CPUs and 2 GiB
(`-Xms1g -Xmx1g -XX:+UseG1GC`), warms it up, then drives it with k6 for the measured window. The
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
  `cpu.stat`, as average cores and per request, and the periods the CPU quota throttled it with
  the throttled time summed over every CPU's run queue, which can exceed the wall time. JFR's `jdk.CPULoad` shares are kept too, but they are taken against the host's CPU
  count, not the container's limit.
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
| `-PwarmupSeconds` | `150` | Warmup load before the measured window. On an `ubuntu-latest` runner PetClinic's throughput still climbed for about 45 s after a 60 s warmup. |
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
- The window was not steady: its last third served more than 10% more requests than its first,
  so the JIT was still compiling when measurement began (raise `-PwarmupSeconds`), or more than
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
beside the numbers and are not a failure. The summary also warns about a run whose PetClinic
averaged under 1.9 cores, since its throughput may measure k6 or Postgres instead, and marks with
`*` a change whose median lies inside `none`'s own minimum to maximum.

## Results

Under `benchmark-overhead/build/results/<config>/`:

- `runs.csv`: one row per run with every metric.
- `summary.md`: per variant, each metric's median with its minimum and maximum, and the median's
  change against `none`; then the skipped classes.
- `metadata.properties`: the runner's OS and CPU count, the PetClinic image's `java -version`, the
  agent jar's SHA-256 and modification time, the agent repository's commit and whether its tree was
  dirty (a local jar can predate the commit), the PetClinic commit, the collector
  directory's commit, resolved image digests, and the repeats, window and warmup used.
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
