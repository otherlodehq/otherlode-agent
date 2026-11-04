# Runtime overhead benchmark

Measures what the agent costs a real service: `spring-petclinic-rest` on Postgres, driven by k6,
with and without the agent. It is a standalone Gradle build, not part of the root build or its
`check`, and it needs Docker.

## What it measures

Each run starts a fresh Postgres, starts PetClinic in a container pinned to its own cores and limited to 2 GiB
(`-Xms1g -Xmx1g -XX:+UseG1GC`), warms it up until throughput is steady, then drives it with k6 for the measured window. The
agent exports to a real `otherlode-collector` container, logging only, so the flush's encoding and
send are the real ones and their cost is in the numbers.

Before the matrix, the harness measures the agent's class listing once. In a container from the PetClinic
image, on PetClinic's cpuset, `javac` compiles `src/main/resources/ListingCost.java` against the agent jar,
and a separate JVM runs it, so the listing meets the cold JVM a starting application gives it. It calls `ClassLister.list()` five times in one JVM against `/app/petclinic.jar`, using the
config's `includePackages` and no excludes, and times each call with the thread's CPU time and the wall clock.
The first call is the cold one a starting JVM pays on the agent's listing thread; the agent builds
the lister in `premain`, so the timed call covers `list()` alone. The calls run on a thread of their
own with nothing touching `java.lang.management` first, so the first pays the platform MXBeans'
first use as the agent's does. The agent's `onRoots` work, judging the classes its listing watch
saw, needs an `Instrumentation` and is not timed. The harness records its CPU and wall milliseconds, the median
CPU milliseconds of the four calls after it, and the number of classes listed. A null listing fails the run
before any variant starts.

Per run it records:

- **Startup:** milliseconds from just before the container is created to the first 200 from
  `/petclinic/actuator/health`, polled every 100 ms from the host, Spring Boot's own "process
  running for" seconds, and the CPU the container used and the steal time of its cores over that
  interval. Every variant, `none` included, has the agent jar copied in, so the copy costs them all
  alike.
- **k6:** throughput, request count and iteration count, `http_req_duration` p50, average, p95 and
  p99, `iteration_duration` average and p95, the dropped-iteration count (0 in a closed loop), and
  the checks pass rate. Each request carries a `name`
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

## Startup only

The time to first 200 is the number that moves most from night to night, because it is bound by the
runner's CPU. GitHub's `ubuntu-latest` is mixed hardware: across 28 nightly runs the `none` JVM's
boot ranged from 9 s to 19 s. The agent against `none` ratio holds across nights, but within one run
the agent's boot swings more than `none`'s, so six repeats do not tell a small change from the
runner's spread. `-PstartupOnly=true` measures startup alone, so many more repeats cost little: a
repeat starts Postgres and PetClinic as a full run does, records the startup figures, and stops
PetClinic gracefully. It has no warmup, no window and no class listing. The variants rotate as they
do in a full run, and the collector runs, so the agent exports as it does there. One start of
`none` before the first repeat is not recorded: it is the coldest of the job, since nothing has read
the image's files yet.

Results go to `benchmark-overhead/build/results/<config>-startup/`, with the same `runs.csv`,
`metadata.properties` and `summary.md` files as a full run. The summary shows each variant's median
with its interquartile range, and each agent variant's median against `none`'s as a change and a
ratio. Four metrics:

- `startupMs` and `bootProcessSeconds`, as in a full run.
- `startupCpuMs`: the CPU the container used from its start to its first 200, from its cgroup's
  `usage_usec`.
- `startupStealMs`: the CPU time the hypervisor took from PetClinic's pinned cores over the same
  interval, from the `steal` field of `/proc/stat`, read through a running container so a Docker
  Desktop VM is read as a Linux runner is. It is left out when the reading fails. A hypervisor that
  does not report steal to its guests shows 0 here, so a 0 says nothing was reported, not that
  nothing was taken.

Every run, startup-only or not, also records the runner's CPU model and count in
`metadata.properties` as `runner.cpu.model` and `runner.cpu.count`, from `/proc/cpuinfo`. Both are
`unknown` where that file has no `model name` line, as on arm64 Linux, or does not exist, as on
macOS.

In CI the `startup` job runs this on the headline config with 21 repeats and uploads the results as
`overhead-headline-startup`. The dispatch input `startup_repeats` sets the count.

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

Four variants are known; `-Pvariants` (below) picks which run. By default three of each config run:
`none` (no agent), `agent` (default options), and `agent-baseline` (`staticBaselineEnabled=true`).
The fourth, `otel`, runs the OpenTelemetry Java agent. Every Otherlode run uses
`exportUrl=http://collector:4319` and leaves `flushIntervalSeconds` at its default of 60, so three
flushes fall in the default 180 s window.

`-Prepeats=N` (default 6) repeats the whole set. In round `r` the variants run in the order rotated
by `r` within the chosen list, so no variant always goes first.

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
| `-PcollectorDir` | required | A local clone of `otherlode-collector`; its `Dockerfile` is built with `docker build` and BuildKit. |
| `-Pconfig` | `headline` | `headline` or `ceiling`. |
| `-Prepeats` | `6` | Runs per variant. A multiple of three gives each variant each position equally often. |
| `-PwarmupSeconds` | `600` | The cap on the warmup load before the measured window. |
| `-PwarmupSliceSeconds` | `30` | Length of each warmup slice. |
| `-PwarmupMinSeconds` | `90` | The least warmup before steadiness may end it. |
| `-PwarmupSteadyDrift` | `0.03` | How far a slice's throughput may differ from the slice before it, as a fraction, and still count as steady. |
| `-PwindowSeconds` | `180` | Length of the measured window. |
| `-PstartupOnly` | `false` | `true` runs the startup-only mode above. |
| `-Pvariants` | `none,agent,agent-baseline` | Comma-separated variants to run, in rotation order. Ids are `none`, `agent`, `agent-baseline` and `otel`. Unknown or repeated ids are refused, and `none` must be listed. |
| `-PotelAgentJar` | none | The OpenTelemetry Java agent jar. Required when `-Pvariants` lists `otel`. When set, every variant's container gets it. |
| `-Pload` | `closed` | `closed` or `open`; see "Open-loop load". |
| `-PiterationRate` | calibrated | With `-Pload=open`, the arrival rate in iterations per second. Refused with `-Pload=closed`. |
| `-PopenRateFraction` | `0.25` | With a calibrated open-loop rate, the share of `none`'s steady closed-loop iteration rate to run at. Above 0 and at most 1. |

Give both paths absolute, as above, or relative to `benchmark-overhead/`: `-p` makes that Gradle's
start directory, so a path relative to the repository root names nothing. The harness stops if
either is missing.

Without `-PagentJar` and `-PcollectorDir`, `test` runs only the unit tests of the harness's own
parsing and summary code. With just one of them, the build stops with a message.

The first run builds the PetClinic image (a clone and a Maven build); later invocations reuse
Docker's layer cache. The build pins PetClinic to commit `afc8fc1d3b8ec8de69414482b3bd70a1da78cb95`.

## Against the OpenTelemetry Java agent

`-Pvariants=none,otel,agent` adds the OpenTelemetry Java agent as a third JVM, so the same app under
the same load shows what Otherlode costs next to what an accepted industry agent costs.
`benchmark-overhead/OTEL-COMPARISON.md` is the design and its reasons.

```
benchmark-overhead/scripts/fetch-otel-agent.sh /path/to/empty-dir
./gradlew -p benchmark-overhead test \
  -PagentJar=$PWD/build/libs/otherlode-agent-0.1.0-SNAPSHOT.jar \
  -PcollectorDir=/path/to/otherlode-collector \
  -PotelAgentJar=/path/to/empty-dir/opentelemetry-javaagent-2.32.0.jar \
  -Pconfig=headline -Pvariants=none,otel,agent
```

`scripts/fetch-otel-agent.sh <empty-dir>` downloads agent 2.32.0 and its `.sha1` and `.asc` from
Maven Central and checks them against values pinned in the script: the size, the published SHA-1
against the computed one and the pinned one, the pinned SHA-256, and the signature, using the
OpenTelemetry Java key fetched from `keyserver.ubuntu.com` into a throwaway keyring and checked
against its pinned fingerprint. It refuses a directory that exists and is not empty, exits non-zero
on any mismatch, leaves only the jar in the directory, and prints the jar's path.

The `otel` JVM adds `-javaagent:/app/opentelemetry-javaagent.jar`,
`-Dotel.exporter.otlp.endpoint=http://otlp-sink:4318` and `-Dotel.service.name=petclinic` before
`-jar`. Everything else stays at the agent's defaults: every instrumentation on, every request
traced, and traces, metrics and logs sent over OTLP `http/protobuf`. That is the agent as an
adopter first installs it, and it does a different job from Otherlode's counting. Sampling would
lower its cost.

The harness starts the OTLP sink once per matrix, only when `otel` is listed: a container from the
PetClinic image running `src/main/resources/NoopOtlpSink.java` (`java /app/NoopOtlpSink.java 4318`,
network alias `otlp-sink`) on the cores Postgres and the collector share. It answers every `POST`
with 200 and an empty `application/x-protobuf` body after reading and discarding the request, and
counts the requests and bytes per path. `GET /counts` answers one `<path> <requests> <bytes>` line
per path; anything else is 404. The agent therefore pays for building, batching, encoding and
sending its telemetry, and nothing for what a receiver does with it. The harness reads `/counts`
from the host before and after each `otel` run, startup-only runs included, and records the
differences as `sinkTraceExports`, `sinkMetricExports`, `sinkLogExports` and `sinkBytes`. A run
covers the warmup, the window and the shutdown flush, so the counts do too.

When `-PotelAgentJar` is set, every variant's container gets the jar at
`/app/opentelemetry-javaagent.jar`, as every variant gets the Otherlode jar, so the copy costs none
of them more than another.

The `summary.md` of a run that includes both `agent` and `otel` gains a last column, `agent vs otel`:
the change of `agent`'s median against `otel`'s, marked `~` when their minimum-to-maximum ranges
overlap, in which case the two are comparable on that metric. A footnote says so. The startup-only
summary has the column too and marks overlap by the same minimum-to-maximum rule, although its
cells show interquartile ranges. The `k6` metrics gain `httpReqP50Ms`, the median
`http_req_duration`, ahead of the average.

## Open-loop load

A closed loop at PetClinic's CPU limit makes latency mostly queueing: it moves with throughput.
`-Pload=open` measures the time an agent adds to a request instead. The warmup is the same
closed-loop warmup, so the JIT warms as it does in a closed run. The measured window then runs
k6's `constant-arrival-rate` executor at a fixed rate for `windowSeconds`. k6 drops an iteration
whenever no initialised virtual user is free at its start time, so the harness pre-allocates the
larger of 4 virtual users per PetClinic core times 4 and the iterations that start in one second at
the rate, so an iteration can take up to about a second without exhausting them. At 123.5
iterations/s on one core that is 124. k6 starts a virtual user beyond those only after it has
dropped an iteration, so the maximum, 200 or twice the pre-allocation, whichever is higher, never
saves a run from the dropped-iteration rule. Each virtual user keeps its own connections, so
PetClinic holds about as many keep-alive connections as there are pre-allocated users, in every
variant alike. `k6/overhead.js` builds the scenario from
the environment variables `OPEN_RATE_PER_MIN`, `OPEN_DURATION_S`, `OPEN_PRE_ALLOCATED_VUS` and
`OPEN_MAX_VUS`; the rate is whole iterations a minute, which expresses a fractional rate per second
exactly. The harness passes neither `-u` nor `--duration` to k6 in this mode, since either would
replace the scenario. Results go to `benchmark-overhead/build/results/<config>-open/`.

The rate is `-PiterationRate` (iterations per second) when given. Otherwise the harness calibrates
it once before the matrix: it starts Postgres and a `none` PetClinic as a full run does, runs the
closed-loop warmup until steady, takes the iterations per second of the last slice, and multiplies it
by `-PopenRateFraction` (default 0.25). PetClinic then has headroom by design, so the saturation
rule does not apply. A run is invalid instead when it dropped an iteration, or when PetClinic
averaged 80% or more of its pinned cores, since its requests then queued. The calibration's warmup
reaching its cap is not a failure and is recorded as `openIterationRateCalibrationSteady=false`.
`metadata.properties` records `openIterationRate`, `openIterationsPerMinute` (the rate k6 ran),
`openIterationRateSource` (`given` or
`calibrated`), and for a calibrated rate `openIterationRateMeasured` (the closed-loop rate before
the fraction) and `openRateFraction`; `summary.md` gives the rate and how it was obtained under
"Open loop rate". The calibration's slices are in `calibration/`.

A fixed rate starts the same number of iterations in every third of the window, so the request
counts cannot show a JVM still compiling. In an open loop the script also exports each third's
median request latency, and `warmupDrift` is the first third's median over the last third's: above
1 the service sped up through the window, below 1 it slowed. The drift rule below judges it with
the same bounds.

There is no startup-only open loop; the two together are refused.

## Run it in CI

The `benchmark-overhead` workflow runs nightly on `master` at 03:17 UTC with the defaults, and by
hand (`workflow_dispatch`), which takes `collector_ref` (default `master`), `repeats` (default 6) and
`startup_repeats` (default 21) and `startup_only` (default false, which skips both configs). It
runs both configs, and the startup-only job, in parallel on
`ubuntu-latest` and uploads `benchmark-overhead/build/results` as an artifact, kept 90 days, even when a run fails. Each config's `summary.md` is added to the job
summary. Results live only in the run: nothing is committed. A run waits for one still in progress
rather than overlapping it. GitHub disables a public repository's schedule after 60 days without
activity, and the Actions tab re-enables it.

The `benchmark-otel-comparison` workflow runs only by hand (`workflow_dispatch`), so the nightly job
is unchanged. It takes `collector_ref` (default `master`), `repeats` (default 6), `startup_repeats`
(default 21), `window_seconds` (default 300), `open_rate_fraction` (default 0.25, passed to the
open-loop job as `-PopenRateFraction`) and `jobs` (`all`, `closed`, `open` or `startup`; default
`all`), and waits for a run of itself in progress. `jobs` picks which of the three jobs run;
the default runs all of them. The jobs run at once, each on a runner of its own, each with `-Pvariants=none,otel,agent` on the
headline config, so every comparison is within one machine:

| Job | Runs | Artifact |
|---|---|---|
| `closed` | `-Pload=closed`, `repeats` of 300 s windows | `otel-comparison-closed` |
| `open` | `-Pload=open`, the calibrated rate, `repeats` of 300 s windows | `otel-comparison-open` |
| `startup` | `-PstartupOnly=true`, `startup_repeats` | `otel-comparison-startup` |

Each job builds the shaded jar, checks out the collector, runs `scripts/fetch-otel-agent.sh`, which
fails the job on any mismatch before a run starts, then runs the matrix, uploads
`benchmark-overhead/build/results` kept 90 days even when a run fails, and adds its `summary.md`
to the job summary.

## What makes a run invalid

An invalid run fails the build and stops the matrix. The steadiness rule below is the exception: it
is judged per variant once the matrix has run. The results written so far, and the failed run's log,
stay in the results directory, and the summary lists why under "Why the run is invalid".

- The class listing returns null or its program fails, in which case the matrix never starts.
- PetClinic exits early, never logs its "Started PetClinicApplication" line, or its log contains
  `VerifyError`, in any variant.
- The k6 checks pass rate is below 1.0. The script checks every response's status.
- PetClinic was not saturated: its container averaged under 90% of its pinned cores over the window
  (`cpuCoresAvg`), so the closed loop was limited by k6 or Postgres and its throughput would not
  describe PetClinic. CPU per request is still valid in such a run, but the run fails regardless,
  after the one repeat per run that may run again (below).
- A variant's windows were not steady: the median over its repeats of the last third's request
  count divided by the first third's lies above 1.10, so the JIT was still compiling when
  measurement began (raise `-PwarmupSeconds` if the warmups hit their cap; lower
  `-PwarmupSteadyDrift` or lengthen `-PwarmupSliceSeconds` if they were judged steady), or below
  0.90, so something degraded through the window. A variant with a run that recorded no drift is
  invalid too. A single run drifts with runner noise in either direction, so with three or more
  repeats one run past 10% does not fail its variant; with one or two, the median is that run or
  the mean of the two. The summary names each completed run that drifted past 10% on its own, and a
  run that failed for another reason after its window names its drift in its failure when it lay
  past 10%.
- For `otel`, in full and startup-only runs: the sink's `sinkTraceExports` difference is 0 (the
  agent exported no trace); PetClinic's log has no `opentelemetry-javaagent - version:` line (the
  agent did not start); a line starting `[otel.javaagent` is at ` ERROR `; or any line contains
  `Failed to export`. The Otherlode rules below, and the skipped-class count, do not apply to it.
- In an open loop: k6's `dropped_iterations` count (`k6DroppedIterations`, 0 when k6 reports none)
  is above 0, so the virtual users could not start every iteration at the arrival rate; or
  PetClinic averaged 80% or more of its pinned cores (`cpuCoresAvg`), so its requests queued.
  Neither is an environmental problem, and neither takes a retry. The saturation rule above does
  not apply to an open loop; the drift rule does, on median latency (see "Open-loop load").
- For an agent variant (`agent`, `agent-baseline`): the agent logged an error (`SEVERE: otherlode:` before Spring Boot sets up
  logging, a line at `ERROR` naming `otherlode:` after); the collector's `/metrics`
  did not show `otherlode_collector_ingest_accepted_total` rising for deltas and for the manifest
  (and the static baseline, for `agent-baseline`), or showed `otherlode_collector_ingest_rejected_total`
  rising; or the run's manifests carried no probe or no endpoint, or a disabled endpoint module.
  Delta batches go out even when empty, as a heartbeat, so accepted deltas alone would not show
  that anything was instrumented.

A repeat whose only problem is saturation runs once more, since one repeat can dip under the 90%
floor by chance on a noisy runner. One repeat per run may do so. The discarded attempt keeps its
run directory as `<variant>-r<repeat>-attempt1`, stays out of `runs.csv` and the medians, and the
summary lists it with its problem under "Repeats that ran again". A second attempt that fails, or a
saturation failure after the run's one retry, fails the run and names every attempt of that repeat.
Any other problem fails the repeat at once.

The startup-only mode applies the rules on PetClinic's log and the agent variant's rules. It has
no window, so the k6, saturation and steadiness rules do not apply.

For every agent run the harness also counts the classes the agent skipped, from the manifests, and
names those it logged an `instrumentation failed for <class>` warning for. They are published
beside the numbers and are not a failure. The summary marks with
`*` a change whose median lies inside `none`'s own minimum to maximum.

## Results

Under `benchmark-overhead/build/results/<config>/` (`<config>-startup/` for a startup-only run,
`<config>-open/` for an open-loop run):

- `runs.csv`: one row per run with every metric.
- `summary.md`: per variant, each metric's median with its minimum and maximum, and the median's
  change against `none`; then any repeats that ran again, the skipped classes and the class listing's
  cost.
- `metadata.properties`: the runner's OS, CPU count and CPU model (`runner.cpu.model`,
  `runner.cpu.count`), whether the run was startup-only (`startupOnly`), the PetClinic image's `java -version`, the
  agent jar's SHA-256 and modification time, the agent repository's commit and whether its tree was
  dirty (a local jar can predate the commit), the variants and load shape (`variants`, `load`), the
  OpenTelemetry jar's name and SHA-256 (`otel.jar`, `otel.jar.sha256`) when one was given, the
  open-loop rate (above), the class listing's `classListingColdCpuMs`,
  `classListingColdWallMs`, `classListingWarmCpuMs` and `classListingClasses`, the PetClinic commit, the collector
  directory's commit, resolved image digests, and the repeats, window and warmup settings used.
- `runs/<variant>-r<N>/`: the raw k6 JSON and log, the JFR file, PetClinic's log, and the
  collector's log lines from the run. A discarded attempt's are in `runs/<variant>-r<N>-attempt1/`.

## Latest numbers

Workflow run 38944648115 on 2026-10-16, agent at `1332f3f`, GitHub's `ubuntu-latest` (4 vCPU), so
PetClinic on one pinned core with four k6 users. Six repeats per variant, every run valid, 35
of 36 warmups judged steady (the other reached its cap with a steady window), no class skipped in
either config. Medians, agent against `none`; a change marked "noise"
lies inside `none`'s own spread.

| | headline (`org.springframework.samples.petclinic`) | ceiling (`org.springframework`) |
|---|---|---|
| probes | 1,489 | 99,685 |
| throughput | -0.8% (noise) | -7.1% |
| CPU per request | +1.0% (noise) | +8.3% |
| p95 / p99 latency | +0.8% / +0.5% (noise) | +6.5% / +7.4% |
| GC pause per 1000 requests | +6.5% | +30.9% |
| RSS | +76 MiB (+7.4%) | +303 MiB (+29.5%) |
| minimum heap used | +10 MiB | +122 MiB |
| metaspace | +19 MiB | +23 MiB |
| startup | 18.3 s to 22.2 s (+21.2%) | 13.5 s to 36.0 s (+166%) |

The headline's startup comes from the startup-only job, workflow run 41236248477 on 2026-10-31,
agent at `b42e026`, on an AMD EPYC 7763 (4 vCPU) with PetClinic on one pinned core. 21 repeats
per variant, steal 0. Medians, with the interquartile range:

| | `none` | `agent` | `agent-baseline` |
|---|---|---|---|
| time to first 200 | 18.3 s (17.7 to 18.7) | 22.2 s (21.1 to 22.4), +21.2% | 23.0 s (21.9 to 23.6), +26.1% |
| startup CPU | 17.9 s (17.4 to 18.4) | 21.8 s (20.8 to 22.0), +21.7% | 22.7 s (21.5 to 23.2), +26.6% |

Every other row of the first table, and the ceiling's startup, come from run 38944648115.

The startup target is the headline's time to first 200 within 25% of `none` on this runner; it is a
target to watch, not a gate. The ceiling has none. Startup on one core is where the agent's
transform work shows most, since class loading, the JIT and the agent share the core; the scripts in
`profiling/` measure where it goes.

## Profiling startup and heap by hand

When a number moves, `profiling/` holds the scripts that found where the agent's startup and heap
went, run on the host against the same PetClinic fat jar and a Postgres container rather than
through the harness:

- `profiling/startup/time-startup.sh <label> [JVM options...]` starts PetClinic once and prints
  Spring's "Started ... in" and "process running for" seconds; repeat each configuration five to
  seven times, interleaved, and take medians. `profile-buckets.py` and `profile-under.py` bucket a
  collapsed async-profiler wall or allocation profile by agent frame.
- `profiling/heap/measure.sh <label> <includePackages|none> <port>` measures live heap, a class
  histogram and NMT before and after the first delivery to a live `otherlode-collector`;
  `histogram-diff.py` and `nmt-diff.py` compare two runs. `DUMP=1` adds a heap dump for MAT.

Each script's header lists the environment it needs. Use a JDK with a CDS archive
(`java -Xshare:on -version` must work) so the no-agent baseline is the one adopters see.

## Attribution

The harness is modelled on OpenTelemetry Java instrumentation's
[`benchmark-overhead`](https://github.com/open-telemetry/opentelemetry-java-instrumentation/tree/main/benchmark-overhead)
(Copyright The OpenTelemetry Authors, Apache-2.0): the container layout, the closed-loop k6 run,
the warmup with a throwaway JFR recording, and `src/main/resources/overhead.jfc`, which is their
file with `jdk.ThreadDump` removed and `jdk.ResidentSetSize` and `jdk.MetaspaceSummary` enabled.
Those files carry their copyright and licence notice and say they were modified. The k6 script is
written for the current PetClinic API and does not use their data.
