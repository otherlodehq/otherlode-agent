# Otherlode agent

## What the agent does

A Java agent that instruments a running JVM app to surface code that is
reachable but never exercised: endpoints never called, methods never
invoked, conditionals that only ever go one way, classes and libraries never
used, and methods that only tests call. The compiler and static analysis
cannot find this, because nothing about the code is unreachable. The agent
reports observations; a collector and the server decide what is dead.

## Where things are written down

This file is a map of the design as it stands and the rules a change must
not break. It is not a log: no dates, no landing orders, no review stories.

- `docs/adr/`: one record per decision, with the options rejected and why.
  Read the ADR before changing what it decided. Amendments sit at the end of
  a record, so its opening can describe a design that was later replaced.
- `docs/site/`: the customer docs, synced to otherlode.dev per release.
  `docs/site/README.md` has the page rules.
- `STATUS.md`: the tracker for what is in flight, parked or known to be
  wrong. Where it and this file disagree about the code, it is right. Git
  history holds what happened.
- `CONTEXT.md`: the glossary. Use its terms with its meanings.
- `README.md`: build, demo and contributor material.

## Design map

Each area says what the code does and where the reasoning lives. The
customer-facing behaviour of each is on the named `docs/site` page.

**Instrumentation** (ADRs 0001, 0002, 0058 to 0061; `how-it-works.md`).
ByteBuddy's `AgentBuilder` wires two tiers into one transform pass. The
method tier decorates each class (`DecoratingTypeStrategy`): the received
bytes run through the agent's own ASM visitors and the header is written as
it came. Entry and omission probes are ASM instructions (`MethodProbes`);
branch probes come from the branch tier's visitors. Type validation is off.
The branch tier keeps the received frames and writes one at each label it
inserts, from an `AnalyzerAdapter`; nothing recomputes a frame. Only the
endpoint tier still uses ByteBuddy `Advice`.

**Probe arrays and the registry** (ADRs 0003 to 0005, 0060). Each class owns
one `long[]`, one slot per probe; a hit is a non-atomic increment. From
class-file version 55 a probe loads the array as a dynamic constant
bootstrapped from `OtherlodeProbeArrays`; below 55 the class gets a field, a
`<clinit>` prelude and a private accessor that falls back to the holder.
`OtherlodeProbeArrays` lives in the `:bootstrap` module, appended to the
bootstrap loader at `premain`. The registry keys a class by name and probe-
layout hash.

**Commit and confirmation** (ADRs 0007, 0028, 0065, 0066; `classes.md`). The
transform computes a class's probes; `onTransformation` commits them once
`make()` succeeded. A committed class stays out of the manifest until a
count is non-zero or the loaded-class sweep sees it; two misses after its
definition attempt ended name it in `failed_classes`. A class the agent
cannot weave (a missing supertype, any rewrite failure) is skipped and
reported.

**Export** (ADRs 0008 to 0012, 0032, 0036; `data-sent.md`). `HttpExporter`
posts protobuf over HTTP/1.1 to `/v1/otherlode/{deltas,manifest,static-baseline}`
on a fixed interval, first flush jittered. A delta batch carries cumulative
`hits_total` per probe, merged with `max()`, and goes out even when empty
as a heartbeat. The manifest is incremental and send-once per class.
`class_id` means something only within one instance's run; every payload
carries a random run id. A failed send leaves the delivered baseline where
it was and the next flush reports the live counts again. The schema is
`src/main/proto/otherlode/v1/otherlode.proto`, published to the BSR by CI.

**Other agents in the JVM** (ADRs 0052, 0053; `compatibility.md`). Shape
(conditions, marks, keys, the layout hash) is read from the class file
through the class's own loader; probes go into the received bytes. Both
tiers are retransformation-capable, so they run after JaCoCo, AspectJ and
Spring's weaver. The first weave stores its plan; a retransformation or
redefinition is woven again from it, and refused only when the class file
changed.

**Class states and the static baseline** (ADRs 0014, 0027, 0029;
`classes.md`). The opt-in static baseline scans `java.class.path` and
`BOOT-INF/classes`/`WEB-INF/classes` once, off-thread, and sends chunks.
The loaded-class sweep reports classes no transformer saw. Runtime-
generated classes (Spring CGLIB, Hibernate, ByteBuddy and Mockito, javassist,
JDK proxies) and coroutine continuation classes are never instrumented,
declared or reported, by `TypeMatchPolicy`.

**Methods and branches** (ADRs 0006, 0021 to 0023, 0025, 0026, 0031, 0037,
0038, 0040, 0041, 0043, 0044, 0046 to 0048, 0054, 0055;
`methods-and-branches.md`). Branch sites from inlined out-of-scope code,
coroutine machinery and switch lowering get no probe but keep their index.
Outcomes carry a condition, guarded lines, a guard, a branch key and a
routine kind. Generated methods are marked by exact bytecode shape per
compiler release; a body in a known outline that matches no read shape is
an unread shape. Kotlin `$default` and Scala default getters become
omission probes on the target.

**Endpoints** (ADRs 0017 to 0020, 0035; `endpoints.md`). One Gradle module per
framework, merged into the agent jar: Spring MVC (annotated, URL-mapped,
functional), Ktor 2 and 3, JAX-RS, the JDK `HttpServer`, and the opt-in
OpenTelemetry route bridge. Advice is inlined Java calling the
`OtherlodeEndpoints` seam in `:bootstrap`. A module whose advice throws
disables itself and is reported in `disabled_endpoint_modules`. Endpoint
modules ignore the include rules.

**Call graph** (ADRs 0024, 0034, 0039, 0042, 0064; `call-graph.md`). Call
edges are read from bytecode at transform time, in-scope callees only,
through pass-throughs and body classes. The collector builds unreached
clusters; an untaken outcome roots the cluster behind it, and an outside
caller relabels an uncalled root.

**Dependencies** (ADRs 0030, 0036, 0051; `dependencies.md`). Usage is read
from the startup listing, loaded-class counts and the adopter's
references, never from library probes. The listing runs on the first flush
or the scan, whichever comes first.

**Configuration** (ADRs 0016, 0033, 0045, 0049; `configuration.md`).
Agent args, then `otherlode.*` system properties, then `OTHERLODE_*`
environment variables, then the default; service identity falls back to
OpenTelemetry's settings. Without include rules the agent logs an ERROR and
disables itself.

**Test runs** (ADR 0050; `test-runs.md`). An agent in a test JVM with
`testRun=true` marks every payload as a test run and reports to the `test`
environment when none is set; with the static baseline on, it waits up to
15 seconds at shutdown for the scan. The collector keeps a test run out of
production findings and reads its call edges to name the tests that call
production code, so a method only tests call becomes its own root kind.
The agent sets the flag and nothing else.

**Testkit and releases** (ADRs 0018, 0056, 0062, 0063; `testkit.md`,
`testkit-api.md`). The testkit is an in-process collector, one per test
JVM on port 4319 under the JUnit extension. Its public API is a
compatibility surface pinned by the ABI dump. Agent and testkit share one
version; a release is cut locally and published from its tag, only when a
maintainer asks.

## Invariants

A change that breaks one of these needs an ADR first.

- **No silent absence.** Anything the agent does not count is reported as
  such: skipped, unreported and failed classes, disabled endpoint modules,
  unread shapes, dropped sites. A fatal problem disables the agent entirely,
  since a missing instance is visible and a zero-hit one reads as dead code.
- **A woven class loads, fails and reflects as the unwoven class does,
  apart from counting.** Keep received frames; `getCommonSuperClass`
  throws on purpose. `WovenClassVerificationTest` defines every woven class
  of the corpora and the version matrix beside its unwoven twin.
- **Shape comes from the class file**, never from the received bytes when a
  class file exists, so other agents never change a result.
- **The hot path is one array increment.** No lock, map, allocation or
  atomic on a probe hit.
- **Nothing is published before it is confirmed.** Probes commit in
  `onTransformation` and stay out of the manifest until the class is
  confirmed defined. The wire has no retraction.
- **The agent reports observations.** How long a path must stay at zero
  before it counts as dead, confidence thresholds and the "dead"
  classification belong to the collector and server. The agent's job is to
  report raw counts over time accurately and cheaply.
- **Inlined code is Java and names no `kotlin.*` type.** Advice lands in
  the adopter's class, and the shaded jar relocates the Kotlin stdlib, so a
  `kotlin.*` reference in advice or a framework helper breaks at run time.
  `verifyAgentJar` fails the build on one.
- **Wire changes are additive**, checked by `buf breaking`. A new field
  needs the collector's bindings bumped, since its redaction strips fields
  it does not know.
- **Compiler shapes are recognised by exact body per read release**, with a
  canary for new releases; anything else is an unread shape, never a guess.
- **Docs follow behaviour, not every code change.** A change that adds
  behaviour a customer can see or set, or modifies behaviour a `docs/site`
  page documents, updates that page in the same commit. A refactor, a fix
  that restores documented behaviour, or an internal change needs no docs
  change. Update the design-map paragraph above only when the change makes
  it wrong, and add a new decision's ADR number to its paragraph. Keep a
  paragraph to what the code does now; history goes to the ADR and
  `STATUS.md`.

## Development workflow: TDD

Build this project test-first. For any unit of behaviour (a `ProbeRegistry`
method, config parsing, backoff or baseline logic, a matcher, a wire-payload
shape), write a failing test that pins down the intended behaviour before
writing the implementation, then implement to make it pass, then refactor
with the test as a safety net. Red, green, refactor, not tests written
after the fact to document what the code already does.

This applies most cleanly to the ordinary Kotlin code (registry, config,
exporter, scheduler, matchers), where behaviour is unit-testable in
isolation. The two instrumentation tiers are harder to drive test-first in
the strict sense, since their real behaviour only shows up once bytecode is
woven into a loaded class. For those, prefer integration-style tests that
instrument a small fixture class and assert on the resulting probe hits,
written before or alongside the instrumentation code, not skipped.

## Comments

KDoc/Javadoc on public types and functions, not inline comments. Reach for
an inline `//` comment only when the implementation itself is genuinely
unintuitive: a non-obvious invariant, a workaround forced by a specific
constraint, something a reader would otherwise misread. Don't narrate what
straightforward code already says.

Run the `humanizer` skill over any comment or KDoc/Javadoc before treating
it as finished. In practice this mostly means: no em dashes, no stock
AI-sounding phrasing ("crucial," "seamless," "robust," "leverage," and
similar), and no sentence that only restates what the code already says.

Don't write comments that describe code relative to time, such as "now,"
"currently," "previously did X" or "new in this change." A comment like that
is stale the moment something else changes, and git history already
carries that context. Describe what the code does and why, not when it
got that way. `STATUS.md` is the exception, since a tracker is about the
state of things rather than about the code.
