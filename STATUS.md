# Status

Working notes on what is in flight and what is parked. Not a changelog; git
history covers that. `docs/adr/` holds one record per decision, `CONTEXT.md`
the glossary, `docs/site/` the customer docs, and `CLAUDE.md` the rules a
change follows.

## Pre-release checklist

What stays open before the release, across all three repos, since a release
ships them together. The release publishes the agent (with its testkit) and
the collector as open source. `otherlode-server` stays closed source and
hosted (server ADR 0001), so every adopter's collector forwards to one
multi-tenant backend.

1. **Re-run the overhead on the commit to be tagged.** `docs/site/overhead.md`
   and `benchmark-overhead/README.md` quote run 38944648115 on `1332f3f`
   (2026-10-16), with narrow startup at +21.7%, inside the README's 25%
   target. Re-run on the tagged commit and update both pages if a figure
   moves.
2. **Cut the releases** (ADRs 0062 and 0063, collector ADR 0006). Cutting
   is Luke's call. Collector `v0.1.0` goes first, since the agent's
   `release.sh` refuses a release until it exists. The agent's needs its
   `## [0.1.0]` changelog section written. Before the first release of each,
   make the collector's GHCR package public, and make the org secret
   `OTHERLODE_DEV_DOCS_TOKEN` visible to this repo and the collector, or the
   release's docs job skips the sync to otherlode.dev with only a notice.
   Still unknown: whether a dropped Portal deployment frees its version. A
   `rehearsal/1-again` tag answers it. Workflows outside the secret-holding
   jobs use v4 of every action, several majors behind.
3. **The quickstart** in otherlode.dev's `start/` (that repo's ADR 0001).
   The agent's and collector's pages and the overview are written.
4. **Review all customer copy.** Copy edits are pre-approved until
   release (2026-10-16), and the whole corpus is reviewed once before it:
   `docs/site`, the README, and the server's UI and docs.
5. **Before the first customer:** the server STATUS's "Go-live
   checklist" (the company, the privacy policy, the site, the front door,
   one snapshot refresh measured on db-f1-micro). No collector can be given
   an ingest URL until the front door exists. None of it blocks publishing
   the agent.

After release: what "Parked" lists, the server's performance-only
deferrals, gzip, a collector config file, and agent-level redaction
(the server STATUS's parked "Redaction in the agent", with its trigger).

## TODO

### The class listing's cost on demo-spring and a large classpath

The overhead page states the listing's cost on PetClinic's jar from run
38949019169: a first call measured in a JVM of its own on one runner core
took about 100 ms of CPU with the narrow rules and 210 ms with
`org.springframework`. The harness measures it on every run. Measure it
still on demo-spring and on a large classpath.

Known gaps in what it lists, all rare, all listing less than the JVM loads:

- JDK 17's `META-INF/INDEX.LIST`, which can name jars outside `Class-Path`
  (JDK 18 dropped it).
- Boot `PropertiesLauncher` `loader.path` folders (documented as a blind
  spot; the server's coverage rule limits the damage).

### Reminders for expiring keys and credentials: to decide

Raised 2026-10-05 by Luke while creating the release signing key. Nothing
reminds anyone when a key, token or certificate the project depends on is
about to expire, and an expired signing key stops releases cold (the Portal
rejects a signature from an expired key). To decide: where the reminders
live (a calendar, a scheduled job that opens an issue, the password
manager's own reminders) and how far ahead they fire, then inventory every
credential with an expiry or a rotation date across the three repos and the
hosted stack, each with its owner and renewal steps.

Known so far:

- **Release signing key**, `Otherlode Releases <releases@otherlode.dev>`,
  created 2026-10-05: primary (certify only) `6ADE E1CA E6B2 E45E 563B
  B72A 7ECB 167D B0FA 59B3`, signing subkey `62EB A55C 989D 53C1 7D6B C63F
  8CD7 0F20 BC8D 3F42`, both RSA 4096 and expiring on 2028-10-04. Published
  to keys.openpgp.org (identity verified) and keyserver.ubuntu.com, checked
  2026-10-05. Remind a month ahead, by 2028-09-04. Extending it needs the primary secret key and passphrase from
  the password manager; after `gpg --quick-set-expire`, re-send the public
  key to keys.openpgp.org and keyserver.ubuntu.com and replace the
  `SIGNING_KEY` secret (ADR 0063).
- **Central Portal user token**, "otherlode-agent GitHub release", created
  2026-10-05 on Luke's Portal account, expiring 2027-10-05. It is the
  `MAVEN_CENTRAL_USERNAME` and `MAVEN_CENTRAL_PASSWORD` pair in the agent
  repo's `release` environment (ADR 0063). Remind a month ahead, by
  2027-09-05: generate a new token, replace both secrets, revoke the old one.
  An expired token fails the release workflow at its upload, before anything
  is published.
- To inventory: the GitHub and Buf tokens CI uses, the WorkOS and GCP credentials behind the hosted server, and the
  `otherlode.dev` domain registration.

### A class that failed to initialise: the server deploy

Settled on 2026-10-16 (agent ADR 0071, server ADR 0070, both with
amendments; `CONTEXT.md`). Built in every repo; the server's side is on
its master at `133a73b`. GitHub Actions started no job for it (nor for
`80fb450` before it): the organisation's billing refused them. Once
billing is fixed, re-run the CI workflow for master's head; it migrates
(0006), deploys and syncs the server docs to otherlode.dev.

### HotSwap of a woven class with changed code: to grill

Raised 2026-10-04 while landing ADR 0060. A woven class whose class file
changed under a running JVM is refused on a re-weave (ADR 0053, amended): the
JVM rejects the redefinition and the old code keeps running woven, so with the
agent attached an IDE's HotSwap of edited code fails, all of a batch together.
Only development meets it, chiefly debugging a test with the agent on the test
task; the README says so beside the testkit's set-up. Below class-file version
55 nothing else was possible, since new bytes would need the probe field a
redefinition cannot add. From 55 a class carries nothing added, so the agent
could re-scan instead: analyse the new class file, weave it with a fresh plan
whose layout hash gives it a fresh registered array, and let the HotSwap
through, the new code counting. To settle: what the manifest says of the old
layout's probes, frozen beside a second entry for the same class (a method the
edit deleted would read as never hit unless a collector learns the old entry
is superseded, which may need a wire field), and whether that is worth it for a
development convenience.

### The endpoint tier has no confirmation

`EndpointInstrumentation` stages and commits declarations from its own
listener for the ADR 0007 reason, so a class that declares endpoints and then
fails to define leaves rows nothing will ever increment: a route reading as
never called, the endpoint form of what ADR 0028 fixed for probes.

Deferred with its reason in that ADR rather than left to omission. The
exposure is narrower than the probe tier's, which registers every class the
agent weaves: `JaxRsModule` is the only module that declares from inside a
transform, and every other module declares at runtime from a framework object
that already exists, so its class certainly loaded. The link, when it is
wanted, is `PendingDeclarations.begin()` taking the declaring class name and
`commit()` attaching it. Not `EndpointEntry.handlerClass`, which happens to
hold the same string for JAX-RS but is a display label: nullable, and set at
dispatch for the other modules.

### A servlet module: decided, to build before 0.1.0

ADR 0074. In order:

1. The server accepts a third `EndpointTemplateKind`, the servlet pattern
   template, as an identity of its own, and the collector's bindings are
   bumped to carry it.
2. The `servlet` module, on the ADR's test matrix. Started: Tomcat 9.0 and 10.1
   are done, with the harness (`ServletContainer`, a suite per container) built
   to take the rest. To come: Tomcat 11, Jetty 10 and 12 (ee10), Undertow 2.3,
   Jetty 9.4 showing the `LINKAGE_ERROR` floor, and a Spring Boot embedded
   Tomcat showing `dispatcherServlet` left out, the H2 console counted and no
   request counted by two modules. The ADR names Tomcat 9 as accepting a late
   `ServletRegistration.addMapping`; 9.0.123 and 10.1.61 refuse it, so the late
   mapping test adds one through Tomcat's own `Context`.
3. Done: the route bridge removes the prefix OpenTelemetry added (ADR 0019's
   2026-10-18 amendment), tested with the servlet module present so
   OpenTelemetry's `/shop/api/*` lands on the module's `/api/*`.

### Running beside OpenTelemetry: to fix before 0.1.0

- With OpenTelemetry's agent listed before this one, OpenTelemetry has
  rewritten servlet and Tomcat classes to carry its virtual-field interfaces,
  which live in its own class loader. The JAX-RS matcher's supertype walk
  cannot resolve them, so the class's weave fails, and a module that matched
  that class (`spring-webmvc` on `DispatcherServlet`) is switched off. The
  bridge's tests list OpenTelemetry's agent after this one, where it does not
  happen.
- The Spring MVC module counts each request twice beside OpenTelemetry's
  Spring MVC library instrumentation, which asks the handler mappings for the
  match a second time to learn the route.

### Endpoint follow-ups

- `endpoints-ktor-3` compiles against Ktor 3.5.2, its newest supported
  release; ADR 0017 says the oldest.
- A handler from Spring's Kotlin DSLs (`router {}`, `coRouter {}`) gets no
  join. Unwrapping to the adopter's lambda waits on reading its compiled
  shape.
- JAX-RS: an interface that inherits its class-level `@Path` from another
  interface is not followed, and a method inherited through a superclass's
  interface with no annotation of its own is not declared; Quarkus serves
  both, Jersey the second. Predates the Quarkus REST gate.
- JAX-RS on Quarkus REST: an abstract `@Path` class with two or more
  subclasses declares endpoints Quarkus never serves (ADR 0020's
  amendment). Reading Quarkus's runtime resource table at
  `RuntimeDeploymentManager.deploy` would be exact; it needs its own grill.
- Vert.x Web: a router mounted both at a literal path and at a parameterised
  path that matches the same request (`/u/1/*` and `/u/:id/*`) counts it
  against the literal one, since only the parent context's mount route,
  which Vert.x keeps private, tells them apart.
- The OTel route bridge gives a parameterised Vert.x mount one endpoint per
  matched value, since OpenTelemetry reports the concrete mount point; the
  bridge is off by default. A fix needs the bridge to recognise those
  values.
- Vert.x Web 4.5.0's "a parameterised mount replaced by another" test failed
  once with no hits at all (1 run in 12, not reproduced since).
- Spring MVC `resources(...)`: a lookup the adopter composes over Spring's
  path lookup that turns a found resource into none leaves the request
  marked, so the route that then serves it is not counted. A filtered nest
  whose composed resource lookup falls back to a resource counts against a
  route declared at the nest's own path. Both are rare.

## Parked

### Deletion manifest over MCP, and why the CI skip was rejected

Explore, not designed. This started as a CI idea: use the fleet's never-hit
set to skip tests that only exercise dead paths. That form is rejected. What
survives is a deletion manifest served to an adopter's own coding agent.

Why the CI skip was rejected, any one of these being enough:

- It reverses ADR 0015. Classification and confidence live at the collector,
  and the testkit stops at query primitives on purpose, as the note below on
  the CI-gate helper already says. A skip is that same policy call made
  louder: a gate fails a build where a human looks, a skip quietly stops
  running something.
- The incentive runs backwards. A skip makes dead code cheaper to keep,
  because it stops costing CI time, which takes away the reason to delete
  it. Deleting the code gets the same CI saving and keeps the pressure on.
- The saving is unmeasured and probably small. A test is skippable only when
  every probe it reaches is never hit in the fleet, and shared setup code
  that production runs sits in almost every test's path.
- A wrongly skipped test is invisible. Nothing in the build says a
  regression went unguarded.

The report form, "these tests guard only dead code", was weighed as the safer
half and rejected too. On the JVM the compiler already finds every test that
names a deleted method, and IntelliJ's Safe Delete lists those tests and
offers to remove them in the same refactor, so the report restates what the
adopter's own tools give for free. It has the dilution problem as well: tests
reaching only never-hit methods is a near-empty set, and relaxing it to
"mostly dead" sets a threshold, which is the ADR 0015 line again.

One case survives both arguments: a test that reaches dead code without
naming it, usually an end-to-end test whose whole point was that path.
Deleting the code leaves it compiling and often passing, and it guards
nothing. Phase two below is the only part that needs per-test coverage.

What replaces it. Serve the server's dead-code findings as a manifest an
adopter's coding agent reads to open a deletion PR. The compiler argument
above is IDE-shaped: it holds for a human with a usage index and Safe
Delete, not for an agent writing a patch, which has neither until it
compiles.

Most of the data is already there. `/unreached-clusters` returns a rooted
connected group of never-hit methods with its routes, members and
never-loaded classes, which is already a deletion unit. With `/never-hit`,
`/never-loaded` and the unloaded rows from `/dependencies`, the manifest is a
composition of endpoints the server serves today, not a new data path. Scope
it per cluster rather than per service, so one cluster is one reviewable PR:
a service-wide manifest invites an agent to open a 400-deletion PR nobody
reads properly. A REST endpoint under `/api/v1` is the contract and the MCP
server a thin wrapper over it, so the contract outlives changes to the MCP
spec.

Age metadata, so the threshold stays the adopter's. The manifest carries how
long each finding has been dead and sets no threshold of its own. Otherlode
cannot know about a month-end batch job, a disaster recovery path or a flag
that is off until it is not, and the adopter can. This is the ADR 0015
posture on a new surface: report the observation and its span, and let the
consumer decide what is old enough to act on. This also answers most of the
trust problem with agent-raised PRs: the judgement sits with the party that
has the context for it.

The store mostly holds this already. `probes.first_seen_at` (migration 0002)
dates a probe, `instances.first_seen_at` and `last_seen_at` bound the
observation window per instance, and `last_hit_at` is derived already as
`max(probe_counts.updated_at) FILTER (WHERE hits_total > 0)` for the
stale-hit finding, null when nothing ever hit it. Putting these on the
never-hit rows is read-path work, not a schema change. Two facts have to
travel together, because either alone misleads: how long the finding has been
dead, and how long Otherlode has been watching. "Dead for 90 days" says nothing
until the reader knows whether the window is 90 days or three years.

The age gap this once waited on is closed. `otherlode-server` keeps location
dates once per service (its "Service-wide dates" entry), so a method's age no
longer stops at the oldest in-scope instance or the retention window, and
every reply carries `watched_since`. The server also keeps service-wide
dates for each keyed branch outcome (its ADR 0025), so branch-level ages are
there too; only a keyless outcome stays capped. A first cut of the manifest
can still be method level, which the JaCoCo join below already assumes.

Phase two, JaCoCo for the vacuous test case. Needed only for the surviving
case above, and only once the manifest stands on its own. JaCoCo's runtime
dumps and resets execution data per test (`IAgent.getExecutionData(true)`),
which gives a test-to-method map to intersect with the never-hit set. Otherlode
never runs in the test JVM: the join is offline on class, method name and
descriptor, the key both already carry. The two agree more than they appear
to, since ADR 0003 takes its per-class count array from JaCoCo, ADR 0015 uses
JaCoCo's own `SyntheticFilter` allow-list with one refinement, and
`BranchSiteAnalyzer` tolerates JaCoCo-instrumented bytecode for the `$default`
and coroutine shapes, confirmed against 0.8.13's offline `Instrumenter`, though
not for the exact-body rules (ADRs 0052 and 0053). Branch level will not
join, because JaCoCo's branch identity is merge-point based while Otherlode's
slots are allocated in encounter order, so a first cut is method level only.
Inline probes stay excluded, as ADR 0025 and the server's rules exclude them
already. Set JaCoCo's `includes` to the agent's `includePackages`: it bounds
the per-test dump cost and lines the two sets up by construction.

Open before any of this is built:

- How the manifest shows a keyless branch outcome, whose dates stay capped
  (server ADR 0025).
- What the manifest says about a finding whose observation window has holes,
  an instance absent for a month.
- Whether the manifest carries the exclusions the server already tracks
  (inline probes, generated methods, disabled-module endpoints) as evidence
  beside a finding, rather than filtering them out where no reviewer sees
  them.
- Whether any of it sits behind publishing. Nothing is published anywhere,
  so no adopter can run the agent yet, let alone point an agent at its
  output.


### A transformer later in the chain replacing the agent's bytes

The third failure past `onTransformation`, and the one ADR 0028 does not
cover. The class is defined and running, with the woven probes gone, so no
comparison against the loaded set can see it: it is present, and its counts
sit at zero exactly like a class that loaded and was never initialised.

The one signal that separates them is reading the loaded class for
`$otherlodeProbeCounts`, and `Class.getDeclaredFields()` resolves every field's
type. Confirmed on JDK 22: a field type `findLoadedClass` reported absent was
loaded by the call itself. A detector that manufactures class loads corrupts
the data it reports on, which is worse than the gap. It also needs a second
agent in the chain that discards its input bytes rather than building on them;
OpenTelemetry's agent carries the same exposure.

### JFR-sampled observed edges

An opt-in overlay marking which static call edges were actually taken, from
`jdk.ExecutionSample`. The wire shape leaves room for an additive marking on
an edge. It gets its own grill once real manifest sizes are known; per-call
dynamic edge tracking stays rejected.

### No CI-gate or threshold helper in the testkit

The testkit stops at query primitives on purpose. Baking in a rule for when
a count is low enough to fail a build is the confidence policy ADR 0015
keeps out of the agent, so composing one is an adopter's own call.

### Small gaps the landed work left, after release

Each was recorded when its work landed and waits for a reason to touch the
code.

Settings:

- No WARNING for an `OTHERLODE_*` variable or `otherlode.*` property the
  agent does not know, so a typo is ignored without a sign.
- Option parsing and service-name detection still run with `enabled=false`.
- No warning for `otelBridgeEnabled=true` with `endpointsEnabled=false`.
- The service-name detector's YAML reader covers block mappings only. A
  Spring name written as a flow mapping is not detected, where
  OpenTelemetry's detector would find it.
- A blank OpenTelemetry system property falls through to its environment
  variable, where OpenTelemetry lets it hide the variable.

Endpoints:

- An endpoint module that throws on a re-weave loses its advice for that
  class without landing in `disabled_endpoint_modules` (logged once per
  module), so its endpoints read as never called from then on. ADR 0017
  accepts it, since a re-weave's inputs equal the first load's.
- Ktor regex routes merge with their parent: `PathSegmentRegexRouteSelector`
  adds nothing to the template. It is absent from Ktor 2.0.3, and its
  `getRegex()` returns a `kotlin.text.Regex` the shaded jar renames, so a
  fix reads `toString()` and has to decide how a pattern holding `/` or `:`
  becomes one segment.
- The OTel bridge duplicates a route under a Spring WebFlux base path with
  OpenTelemetry's library instrumentation, which adds the base path where the
  bridge cannot see the unprefixed route (ADR 0019's 2026-10-18 amendment).
- JSP pages are not counted: each runs inside the container's one JSP
  servlet, which the servlet module leaves out (ADR 0074). Counting a page
  needs a hook in the JSP engine.
- The OTel bridge records OpenTelemetry's `HTTP` verb for an unknown method
  as an endpoint of its own.
- An unclosed brace in a route template swallows the slashes after it, and
  a regex holding `}` (`{p:[^}]+}`) closes early.
- Weld's proxies are not recognised as runtime-generated, since their
  naming has not been read from Weld's source, so a JAX-RS resource under
  CDI counts twice.

Methods, branches and generated code:

- A class kotlinc regenerated from a library's inlined object
  (`Foo$bar$$inlined$sortedBy$1.compare`) keeps its METHOD probe at a
  library line, and reads as never hit when the sort saw one element.
- A nested `try`/`finally` written on one line loses one `FINALLY_COPY`,
  since kotlinc gives the outer copy no line number. Contrived.
- Routine outcomes (ADR 0046): a throw path whose message reads another
  class's Kotlin property, or formats with `String.format`, is not
  `THROW_ONLY`; javac's try-with-resources is read only at the resource's
  null check, which is `NULL_DEFAULT`; a `Throwable` the analyser cannot
  read counts as one when its name ends in `Exception` or `Error`.
- Kotlin `value class` `-impl` methods and kotlinx.serialization's output
  are not `GeneratedBy` values.
- Mockito's named-module helpers (`$MockitoModuleProbe$`,
  `InjectionBase$<n>`) are not recognised as runtime-generated (ADR 0029).
- EclipseLink (`_persistence_*`), OpenJPA (`pc*`) and Ebean (`_ebean_*`)
  weave methods into entities as Hibernate does, and only Hibernate's are
  passed through (ADR 0047). Each needs reading from its own source first,
  and OpenJPA's `pc` is a prefix a person writes, so a prefix cannot match it.
- `LoadedClassSweep` drops every synthetic class, so a multi-file part that
  reached no transformer is not reported.
- Two narrow cases still read a woven class's class file through its loader
  (ADR 0052's amendment): a class with no code source or one of another
  shape, and a class transformed while its jar shape warms up. A loader that
  defines child-first but serves resources parent-first then hands the agent
  its parent's copy. Detecting the mismatch waits until a loader of that
  shape turns up.

Names:

- A Kotlin extension call reads as its static facade call
  (`StringsKt.toDoubleOrNull(value)`), and a mapped built-in as a Java call
  (`this.length()`). Telling an extension apart needs the callee's Kotlin
  metadata. A Scala `object` read through `MODULE$` is a placeholder until
  its shape is confirmed.
- A javac inner-class constructor with no generic types has no `Signature`
  to drop its outer instance by, so the server shows `this$0`. A flag read
  from the class's `InnerClasses` attribute would close it.
- A receiver lambda whose captured values come first gets no
  `extension_receiver` flag, since only the first parameter is read.
- `runDemoStack`'s printer prints JVM names on its endpoint and
  optional-parameter lines, and an any-verb route as `* /checkout` where the
  server shows `ANY /checkout`.

Dependencies and the testkit:

- A `byte-buddy-agent` jar flat in the `WEB-INF/lib` of a war an
  application server deploys is turned away by the sweep's agent-jar rule
  and reads as no dependency rather than as one. An unpacked Spring Boot
  application on the classpath lists it as one (ADR 0030, amended
  2026-10-14).
- A packed Spring Boot jar's nested jars are listed where the fat jar sits
  in the search order, though Boot's loader searches them after the whole
  system classpath. It changes which jar keeps a shared identity's location
  and class names only when the fat jar shares `java.class.path` with other
  entries, as in `java -cp app.jar:x.jar ...JarLauncher`; `java -jar` leaves
  only the fat jar.
- A `Class-Path` token that is not a valid URL makes the JVM skip the whole
  jar, while the listings skip only that token and read the rest
  (`classPathEntries`). Both listings read more than the JVM loads there,
  never less.
- `withHomeAsTilde` turns a home of `C:\` into `C:`, so on Windows every
  `C:\...` location would read as `~\...`, though its KDoc says a root home
  changes nothing.
- The testkit's `awaitSettled` counts two delta batches after its start,
  which assumes one batch per flush, so a flush split into several batches
  can wake it before the hit totals settle. ADR 0036 fixed the same flaw in
  `dependency()`.

Overhead and memory:

- The JMH micro suite runs in no workflow, so it has no leg on any JDK.
- Overhead results live in each run. Committing them to a results branch
  waits until a trend view across months is wanted.
- Condition text is not interned (`MetadataCompaction`), a possible saving.
- A Scala 3 enum costs, for each of its classes transformed, a read of its
  companion and of each parameterised case's class (only that class's
  `ordinal` body), which grows with the square of the case count: about
  6,900 reads for an 80-case enum, once, at load. Not measured since the
  reads were cut to `ordinal`; the 2026-10-15 review measured an 80-case
  enum's analysis pass at 210 ms with the field route these replaced, and
  at 273 ms with full case-class reads, an upper bound for this route. An
  all-singleton enum reads only its companion.
- A local Scala 3 enum's companion and cases each pair the companion by
  reading every class it lists, and the pairing runs two or three times per
  class: about 9 to 11 class reads for a companion and 7 to 9 for a case,
  once at load. Not measured, and local enums are rare.

Ideas nobody has asked for:

- A server-side counterpart to `VisibilityFloorTest`, run over fixture
  payloads.
- A "persistent field never read" finding: a stored column no application
  code reads. It would come from the adopter's own field reads and be
  mostly the server's work.

### Compiler shapes not yet read, after release

What the compiler canary and the reviews found that the agent does not
read. Each reads as an unread shape, never a finding, unless it says
otherwise. ADRs 0054 and 0055 hold the rule.

- Scala 3: backticked case names, an enum that overrides or inherits
  `productPrefix`, and two case names with the same hash. An enum whose
  singleton never reads its name gets no `$name` field and a `$new` calling
  `<init>(I)V`, and every enum after it in the same source file gets
  singleton fields whose suffixes differ (`$name$1` beside `_$ordinal$2`),
  so its singleton's `ordinal` is held as an unread shape too. Scala 3.3
  at its default Java 8 target writes `valueOf` and `fromOrdinal` with a
  `StringBuilder`, which reads as unread shapes until it is read (ADR 0055,
  amended). A local enum declared in a static context, a constructor
  argument (`class Sub extends Base({ enum E ... })`) or a `@static` method
  at 3.10.0, has a singleton `readResolve` calling the host's lazy accessor
  with `invokestatic`, which reads as an unread shape. When a companion's
  `valueOf` reads as an unread shape, so does every other method there
  the JVM knows as `valueOf(String)`, the adopter's included, since which
  one is scalac's is not certain (ADR 0054, amended 2026-10-16).
- Unread shapes are recorded at transform time, before ADR 0007's commit
  point, so a transform that then fails is still counted in the first-flush
  summary, as are outcome counts and the received-bytes count. Classes
  first loaded after the summary are never logged.
- kotlinc: a string `when` whose `null` shares a branch with a literal
  (`"a", null -> ...`) is not read by ADR 0038's lowering, so its collision
  sides read as unread shapes; kotlinx-coroutines' `DebugKt` has one.
- kotlinc 2.2 and later under an explicit `-jvm-default=disable` make each
  implementing class's stub a bridge, which has no probe, so a call typed
  as a super-interface whose sub-interface overrides the default does not
  reach that sub-interface's `$DefaultImpls` body. The default mode from
  2.2 is unaffected.
- A value class as a generic interface's type argument (`Impl : I<V>`,
  kotlinc 2.1 and earlier) gives a stub that boxes through `V.box-impl`,
  then casts and unboxes the result. It is not read and reads as the
  adopter's code (ADR 0026's amendment). `Unit` as the type argument gives
  a stub that pops the result and returns void (`get()V`), not read either.
  Both turn up in a sub-interface's `$DefaultImpls` forwarders too
  (`SV$DefaultImpls.put-<hash>`, `SU$DefaultImpls.put`).
- A data class with a value-class property is not recognised: its
  `componentN` and `copy` names are mangled (`component2-<hash>`), so none
  of its generated methods is marked (kotlinc 1.9.25 and 2.1.21).
- A suspend default returning `Unit` gives a `$DefaultImpls` forwarder that
  ends by comparing the result with the suspended marker and returning
  `Unit`: a sub-interface's under `disable` on every kotlinc, and the
  interface's own under `all-compatibility` before 2.2. Not read, and a
  false never-hit when unused.
- javac 25 at `--release 21` lowers an enum pattern switch through
  `typeSwitch` with ConstantDynamic `EnumDesc` arguments, and every javac
  lowers a qualified enum label the same way. `bootstrapSwitch` reads
  neither, so those cases keep numeric keys (readability only, ADR 0038's
  amendment).
- `$default`'s super-marker null check is not checked by the mask rule.
  Nothing has shown a body where that matters.

### Deliberate v1 boundaries

Not gaps, and not on anyone's list: static attach only (ADR 0013), no
redefinition of a woven class whose class file changed (ADR 0005, which
accepts one whose class file did not), the
static scan not opening `BOOT-INF/lib` nested dependency jars, the
classpath blind spot for app-server, OSGi and plugin-loaded deployments, and
include rules being required, with no `includePackages=*` to ask for every
class (ADR 0033).
Each ADR named has the reasoning and what it would take to change.

## Cloud session build notes

Two things a fresh cloud session needs for this repo's build. Maven Central
has answered 429 through the sandbox proxy, and a session-local Gradle init
script pointing at Google's mirror of it
(`maven-central.storage-download.googleapis.com/maven2`) worked once the
user approved it. The `ktlint` CLI the chunked-build command runs is not
preinstalled; the 1.5.0 release binary from GitHub works.
