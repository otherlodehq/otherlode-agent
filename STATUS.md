# Status

Working notes on what is in flight and what is parked. Not a changelog; git
history covers that. `CLAUDE.md` holds the design in long form, `docs/adr/`
one record per decision, and `CONTEXT.md` the glossary. Where this file and
`CLAUDE.md` disagree about the state of the code, this one is right.

## Pre-release checklist

Drawn up 2026-09-26 from every open item in the three repos' STATUS files,
READMEs and CI. It covers all three repos, since a release ships them
together. The order: what is hard to undo once published, and what would
make an adopter's first report wrong, comes before polish. The release
publishes the agent (with its testkit) and the collector as open source.
`otherlode-server` stays closed source and hosted (server ADR 0001), so every
adopter's collector forwards to one multi-tenant backend.

1. **Run on a real Spring Boot service.** Done on 2026-09-26 against the
   compose stack, through `runSpringDemoStack` and `runShapesStack` (Java
   anonymous classes and a lambda, a companion object, a data class, a
   suspend function, and the Scala 2 and 3 fixture drivers), reading both
   the report and the web UI. Correct: Spring endpoints and their handlers,
   never-loaded classes, anonymous class and lambda names ("second Runnable
   anonymous class in run", "Supplier lambda in run at line 32"), suspend
   function names, untaken conditions with the code only they reach, and
   "always supplied" on Scala defaults. What was wrong is item 2.
2. **Fix the false findings the real runs showed**, most noise first:
   - Scala case-class and object plumbing reads as dead code: `canEqual`,
     `copy`, `equals`, `hashCode`, `productElement` and its `Name(s)`,
     `unapply`, `fromProduct`, `curried`, `tupled`, the companion's
     `toString` and `writeReplace`, and an object's `writeReplace`. Each is
     its own uncalled cluster root. Scala needs what ADR 0026 gives Kotlin
     data classes.
   - Scala objects are counted twice: each `Driver$` method has a twin on
     `Driver` at line -1, the static forwarder scalac adds, so 14 methods
     give 28 rows and every cluster doubles.
   - Both Scala bullets above landed on 2026-09-26 in all three repos
     (ADR 0048): agent `53cf4e9`, collector bindings `7c36b23`, server
     labels `9725015`. Case-class and companion plumbing is `CASE_CLASS`,
     static forwarders `STATIC_FORWARDER` (passed through), an object's
     `writeReplace` `SCALA_OBJECT`, each recognised by the body scalac
     writes, never by line. A first line-based rule failed four review
     rounds on layouts (wrapped parameters, body `val`s, a hand-written
     `copy` with defaults) and hid hand-written code, so it was replaced
     before commit; seven review rounds in all. The rebuilt stack labels a
     Scala 3 run's probes 37 `case_class`, 21 `static_forwarder`, 2
     `scala_object`, and the Scala never-hit lists hold only the fixtures'
     uncalled methods. Not covered, in ADR 0048: Scala 3 enum class and
     singleton-case plumbing, `Enumeration`, `lazy val` plumbing, `Array`
     and `Unit` elements, symbolic names, value-class elements, aliased
     superclass parameters, local case classes that capture a value, and
     Scala versions other than 2.13 and 3.3.
   - The two coroutine bullets landed on 2026-09-26 under ADR 0025, amended
     (`c1b59c5`).
     The stack form of the suspended-marker compare (`dup; invokestatic
     getCOROUTINE_SUSPENDED; if_acmpne`: the debug-probe hook in an inlined
     `suspendCoroutine`, and a `Unit` function's tail-call return) is
     coroutine machinery inside a suspend-shaped method; the marker-slot
     tracking the local-slot shape uses is cleared at the next jump, switch or
     call, since an unrelated later `ASTORE` was being taken for the marker.
     A suspend lambda's `create` and `invoke` (direct superclass
     `SuspendLambda` or `RestrictedSuspendLambda`) get no probe, are not
     declared, and pass through, the ADR 0047 mechanism: `invoke` never runs
     when a library starts the lambda through `create` (stdlib 2.2.21
     `IntrinsicsJvm.kt`), `create` only repeats `invokeSuspend`, which holds
     the body. Put to Luke as "either one reads as never hit", which was wrong
     about `create` (`invoke` calls it); the choice stands on the corrected
     reason. A `runShapesStack` rerun under a fresh version shows neither
     row; the shapes report's branch sites are the three the adopter wrote.
   - The Scala 3 lambda-body bullet landed on 2026-09-26 (ADR 0034,
     amended; `4152a99`). Scala 3 names a body owned by a method `<method>$$anonfun$N`
     (`LambdaLift.newName`, 3.3.4), which the scalac name rule did not know.
     The by-name closure `callByName$$anonfun$1` is not synthetic, so it was
     probed but unflagged; worse, a lambda written in a method or a class
     body (`label$$anonfun$1`, `$init$$$anonfun$1`) is synthetic, so it was
     not probed at all and its branches went uncounted, a gap no fixture
     covered. The rule takes the Scala 3 shape. A lambda Scala 3 lifts out
     of a nested class into the top-level class
     (`…$Inner$$_$bump$$anonfun$1`, created from the nested class) is
     flagged by that expanded name, settled with Luke; a boxing one is
     reached through its expanded bridge (`…$$_$show$$anonfun$adapted$1`)
     to its short-named body. `InlineLambdaHost`
     and `NestedLambdaHost` pin both compilers; `runShapesStack` shows the
     Scala 3 by-name closure folded into `callByName`, as Scala 2's is.
   - The utility-class constructor bullet landed on 2026-09-26 (server ADR
     0034, amended; server `48783b9`, agent `d7af7d4`). A never-hit `<init>` of a class no run constructed and
     no class finding covers, the report's `unjudged_constructors`, is
     treated in the cluster graph as `<clinit>` is: reached through, never
     a root, never listed or counted, and its class can be listed whole
     without it. Settled with Luke over the alternatives of letting a call
     from it not count or dropping it from the graph. Server graph and
     store, the testkit's `unreachedClusters()` and the stub collector all
     apply it.
   - The accessor log bullet landed on 2026-09-26 (ADR 0021, amended;
     `bf47594`).
     kotlinc's accessor for a private constructor, `<init>(params...,
     DefaultConstructorMarker)`, was read as a default-filling constructor.
     Every companion object and sealed class has one, so each logged the
     INFO line. Worse, a call from another class was resolved by dropping
     the accessor's last value parameter: an edge to the wrong constructor
     when the class had one of that descriptor, otherwise a verbatim edge to
     the unprobed accessor, leaving the private constructor with no caller.
     An accessor is now told by its body (exactly one call to a constructor
     of its class, `this(params...)` with only the marker dropped), is no
     default site and not logged, and passes through to the private
     constructor.
   - Not seen in these runs, still open: a named class implementing a
     framework interface reads as an uncalled root (the ADR 0024 gap);
     kotlin-stdlib always reads as used, through `kotlin.Metadata`; a class
     that failed to load reads as unreferenced.
   - An unread body reads as the adopter's code, found after item 3 landed:
     current Scala patch releases make every case class's `hashCode` dead code,
     and kotlinc 2.1 and earlier a never-hit stub per interface default method.
     Settled on 2026-10-03 (ADRs 0054, 0055) and landed on 2026-10-04 in all
     three repos: agent `1ccb7a5` to `a985ad1`, collector `cdf423b`, server
     `b200bc2` and `6dbc56a`. Compiler output the agent has not read is an
     unread shape, never a finding. The TODO entry "An unread body is an
     unread shape" has each step and the shapes still unread.
   - Naming, after release: `Tariff$Companion`, `Cc$` and `Driver$` show
     their JVM names in the UI, and Scala signatures read with Java types and
     `x$0` parameter names. A data class property's getter reads as never
     hit when only `copy`, `toString` and the class's own methods read the
     field; that is true, and noisy.
3. **Another agent ahead of or behind this one.** Done on 2026-10-03;
   ADRs 0052 and 0053. With JaCoCo ahead, every exact-body result is read
   from JaCoCo's output; with this agent ahead, which is Gradle's default
   for an adopter's test task, JaCoCo's report loses every woven class. The
   plan is the TODO entry "Another agent ahead of or behind this one". It
   sits here because the testkit's first report is wrong and the adopter's
   coverage report breaks.
4. **Settle the one-way doors before anything is published.** Settled on
   2026-10-04 in a grilling session (ADRs 0056 and 0057, amendments to 0016,
   0045 and 0054, collector ADR 0005, server ADR 0054) and built the same
   day in all three repos; the TODO entry "One-way doors settled before
   release" lists the commits.
   - Testkit API (ADR 0056): Java and Kotlin callers; the testkit owns its
     result types and shades wire and protobuf; data classes only grow, with
     internal constructors; enums grow in minor releases; findings merge
     across instances as the server's do; seven queries and `rootSite`
     become internal; `explicitApi()` and an ABI dump; American spelling in
     identifiers; `otherlode.testkit.startup.timeout.seconds`; the testkit
     refuses an agent of another version.
   - ADR 0054's wire surfaces: the zero values inside `origin` oneofs become
     `_UNSPECIFIED` and are never written; `UNREAD_SHAPE_STRING_SWITCH`;
     `agent_version` is opaque; a collector that strips unknown fields sets
     `ResourceAttributes.fields_stripped`, and the server makes no claim from
     that run.
   - Option names (ADR 0016, amended): `endpoint` becomes `exportUrl`, which
     refuses a query string or fragment; no option begins with `testkit` or
     `collector`; OTel's `service.version` is read, its
     `service.instance.id` never (ADR 0045, amended); every other name stands.
   - Versioning (ADR 0057, superseded by ADR 0062 on 2026-10-05):
     `otherlode-agent` and `otherlode-testkit` go to Maven Central under
     `dev.otherlode` and share a version from `0.1.0`; the collector, at
     first in lockstep, has its own version (item 6). The version lives in
     the tree and a release is a matching `vX.Y.Z` tag.
5. **Security basics, sized to how the server is hosted.** Settled on
   2026-09-27 in a grilling session: server ADRs 0040 to 0043 and
   collector ADR 0003. The plan, its ten chunks and what is deferred are
   in the server STATUS under "Security for the hosted service". In
   short: WorkOS AuthKit for login (Google, Microsoft or GitHub, or a
   tenant's own SAML or OIDC SSO connection, enforced), with the tenant
   check at login and the server's own sessions; `admin` and `viewer`
   roles; row-level security behind the tenant filter; API keys scoped
   `ingest` or `read` with an ingest limit per tenant; audit events that
   show operator actions to the tenant; hosting on GCP Cloud Run and
   Cloud SQL; nothing costs money before a paying customer needs it.
   All ten chunks have landed: production went live on 2026-09-28, moved
   to `otherlode-production` on 2026-09-30, and has had browser login
   since 2026-10-02. What stays before the first customer (the company,
   the privacy policy, the domain, the front door) is the server STATUS's
   "Go-live checklist"; none of it blocks publishing the agent, but no
   collector can be given an ingest URL until the front door exists.
   - Server: the full Content-Security-Policy landed on 2026-09-27
     (server `788716b`). Its regression guard is chunk 2 of the server
     plan: a CI check on the built `dist/` and a jsdom Select test.
   - Collector: its auth token takes a comma-separated list, and
     `OTHERLODE_COLLECTOR_AUTH_TOKEN_FILE` and
     `OTHERLODE_COLLECTOR_FORWARD_AUTH_TOKEN_FILE` read files that are
     re-read every 30 seconds (collector ADR 0003; chunk 1 of the server
     plan). TLS is documented rather than built: the README's TLS
     section (collector `b838535`, 2026-09-27) says it must sit behind a
     TLS-terminating proxy, since the agent-to-collector hop carries the
     token and literals redaction has not yet seen.
   - Agent: URL schemes are case-insensitive everywhere (2026-09-27).
     `parseEndpoint` lowercases the endpoint's scheme, so `HTTP://host`
     with a token gets the plain-http warning it used to skip; a `FILE:`
     `Class-Path` entry is followed; and `CodeSourceLocation` reads
     `jar:FILE:` and `Jar:Nested:` URLs. `java.net.URL` lowercases only the
     outermost protocol, and `ReferencedClassLocator` matches its prefixes
     ignoring case too, since a custom handler's `toExternalForm` can print
     any case.
6. **Release mechanics.** Grilled on 2026-10-05 with Luke after two research
   passes (what couples the three repos; how to publish to the Central
   Portal), ADRs 0062 and 0063, collector ADR 0006, collector ADR 0005
   amended; ADR 0057 superseded.
   - Versioning (ADR 0062): the agent and the testkit share a version; the
     collector has its own, `v0.1.0` first, cut just before the agent's
     `0.1.0`, and adopters run the latest. Nothing reads the collector's
     version, and the newest collector serves every agent. The one ordering
     rule: before an agent release that changed the schema, the server's
     `master` and the latest collector release carry bindings at least as
     new as it.
   - Cutting (ADR 0063): `scripts/release.sh X.Y.Z` locally (changelog
     section required, the bindings check through `gh api` with an override,
     the release commit, the tag, the next SNAPSHOT); the tag workflow
     refuses a mismatched or SNAPSHOT tag, runs every JDK leg, and uploads
     one Portal deployment through nmcp, both artifacts from the shadow
     component, signed by a dedicated `releases@otherlode.dev` key held in a
     `release` environment only `v*` tags reach. `USER_MANAGED` for `0.1.0`
     (GitHub release a draft until Luke publishes on the Portal), `AUTOMATIC`
     after. The GitHub release carries the agent jar, its `.asc` and
     `SHA256SUMS`, with the changelog section. A manual rehearsal uploads
     `0.0.0-rehearsal.<run>` and drops it.
   - Collector (collector ADR 0006): its own `release.sh`, changelog and tag
     workflow; a `linux/amd64` and `linux/arm64` image on GHCR tagged
     `X.Y.Z`, `X.Y` and `latest` with build provenance; no binaries; the
     redaction WARNING and README say "the latest collector".
   - Landing order, one chunk and one commit each: (1) JDK 17 floor and CI
     leg; (2) the agent's publishing build (nmcp, signing, POMs, sources and
     javadoc jars, the testkit's stdlib on its shadow variant), proved with
     the local bundle; (3) the agent's release and rehearsal workflows,
     `release.sh` with the bindings check, `CHANGELOG.md`; (4) the
     collector's release pieces and rewording; then Luke's steps and the
     rehearsal.
   - Chunk 1 landed on 2026-10-05: the agent, bootstrap, wire, the endpoint
     modules, the testkit, the demos and the general fixtures compile with
     `release 17` and `-Xjdk-release=17` on the JDK 21 toolchain, and CI's
     test matrix is 17, 21 and 25. The agent needed no change: the lambda
     factory's `interfaceClass`, `implInfo` and `spinInnerClass` exist on 17.
     `verifyAgentJar` and `verifyTestkitJar` fail on a class above 61 (an
     entry under `META-INF/versions/N/` is held to Java N). The four Java
     fixtures that use pattern matching for switch compile at release 21 in
     their own task, copied into `compileTestJava`'s output as an input of
     that task; tests that define them skip on 17, per case
     (`ClassFileSupport`). Review moved the probe-window `modern` form from
     release 21 to 17, since at 21 the dynamic-constant probe path had no
     window test on a JDK 17, and narrowed the JaCoCo-ahead comparison to
     drop only the two release-21 classes on 17.
   - Chunk 2 landed on 2026-10-05: `maven-publish`, `signing` and nmcp 1.6.2
     in the root and the testkit, through a shared `gradle/publishing.gradle.kts`.
     Both publish the shadow component, with sources jars (main Kotlin and
     Java only), javadoc jars (a README for the agent, Dokka for the testkit),
     LICENSE and NOTICE in every jar, and `org.gradle.jvm.version` 17 in the
     Gradle metadata, so a JVM 11 consumer fails at resolution. The testkit's
     POM lists kotlin-stdlib at runtime scope, from its shadow variant. Gradle
     names a signing key by the low 32 bits of its id and takes only eight hex
     digits (`PgpKeyId`), so `SIGNING_KEY_ID` is reduced to those and defaults
     to the release subkey's `BC8D3F42`: the `release` environment needs four
     secrets, `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD`, `SIGNING_KEY`
     and `SIGNING_PASSWORD`. `verifyPublication` checks the deployment zip
     (layout, checksums, signatures when signed, POM fields, dependencies,
     both main jars against their shadowJar output and version, the `.module`
     variants), runs in CI on the JDK 21 leg, and runs before every upload,
     since nmcp resolves the aggregation leniently and a testkit that failed
     to resolve would drop out silently. A SNAPSHOT upload is refused when the
     task graph is built. Proved by a signed run with a throwaway subkey-only
     export, ten signatures verified; nothing uploaded. For chunk 3: nmcp
     prints the deployment id only in its log ("Nmcp: deployment bundle '<id>'
     uploaded."), and retries the upload POST up to three times, so a lost
     response can leave a duplicate deployment.
   - Chunk 3 landed on 2026-10-06: `release.yml` (tag `v*.*.*`: guard, the
     whole suite through a callable `gradle.yml`, the upload, then a separate
     GitHub-release job), `rehearsal.yml` (tag `rehearsal/<n>`: upload
     `0.0.0-rehearsal.<n>`, wait for validation, drop through the Portal API),
     `scripts/release.sh`, `scripts/changelog-section.sh` and `CHANGELOG.md`.
     Three Opus review rounds; what they changed is ADR 0063's 2026-10-06
     amendment. The one that would have stopped every release: CI's test job
     ran `verifyPublication` unsigned at the release version, which demanded a
     signature, so signing is now required only when the upload task is in
     the build. Luke's GitHub setup is done and was checked through the API on
     2026-10-06: the `release` environment admits tags `v*` and `rehearsal/*`
     and holds the four secrets, no `CENTRAL_PUBLISHING_TYPE` is set, the
     "Release tags" ruleset covers both patterns with only the admin role able
     to bypass, and immutable releases are on. Workflows outside the
     secret-holding jobs still use v4 of every action, several majors behind;
     bumping them is a follow-up.
   - Luke's steps, needed before the rehearsal. Done on 2026-10-05: the
     `releases@otherlode.dev` group (Google Workspace, external posting on),
     the signing key (fingerprints and expiry under "Reminders for expiring
     keys and credentials") on keys.openpgp.org and keyserver.ubuntu.com, the
     Portal account (`luke@otherlode.dev`), the `dev.otherlode` namespace,
     verified by its TXT record on the apex `otherlode.dev`, and a Portal user
     token. Done on 2026-10-06: the `release` environment and its secrets, the
     tag ruleset, immutable releases.
   - A flaky test now blocks a release: `CodeSourceClassFileTest`'s warm-up
     tests failed on CI on 2026-10-05 (runs 37353518574 and 37360423743, a
     different test each time) and passed on rerun. To fix before `0.1.0`.
   - Agent CI moved to `bufbuild/buf-action` on 2026-09-27, which also
     checks formatting. It runs every check but not the push, which is a
     `buf push --label master` step after it: the action's own push labels
     from a live look at origin and fails once master has moved on. The
     module's default label on the registry moved from `main` to `master`
     to match; `main` stays frozen at `34b5239c`, and nothing refers to it.
     The switch left two registry commits with identical content:
     `24387a85` created `master`, and `91a74c73` moved it at the moment the
     default switched, with the check state only default-label commits
     carry, so most likely the switch itself re-committed it. Buf's docs do
     not say so; unconfirmed. Runs queue per ref, since two publishes in
     flight could move the label back, and a manual run checks a change to
     the workflow alone without publishing.
   - The collector's HEALTHCHECK landed on 2026-09-27 (collector `e5d3e2f`),
     and the server's probe got the same proxy and address fixes (server
     `19f08fa`). The collector's build image is `golang:1.26-alpine`
     (collector `65989bc`), with Dependabot ignoring golang minor and major
     bumps, since `go 1.26.0` in `go.mod` is the floor for importers.
   - Server: it is not published; GitHub Actions deploys it to Cloud Run by
     digest through Workload Identity Federation.
7. **Measure the overhead.** Grilled on 2026-10-04; the entry "Runtime
   overhead: grilled, to build" below. Before release: the PetClinic macro
   harness with its headline and ceiling numbers in the README, the hot-path
   JMH suite, the deterministic CI checks, and, moved before release on
   2026-10-04, the code-size guard, the flush and registry benchmark and the
   nightly schedule.
8. **Weave everything that can be woven, and never crash an adopter's
   class.** Grilled on 2026-10-04; the entry "Probe arrays, validation and
   placeholders: grilled, to build" below. A woven class could throw
   `NullPointerException` where the original ran (ADR 0060), and 312 of the
   ceiling run's 4462 matched classes were skipped, most of them needlessly
   (ADRs 0058, 0059).
9. **Startup and heap.** Grilled and landed on 2026-10-05; the entries "The
   agent's startup and heap: landed" and "`DECORATE` instead of `REBASE`, and
   the entry probe in ASM: landed" below. On the runner (`8139271`) the
   headline's startup overhead went from +76% to +22.4%, inside the 25%
   target, and the ceiling's retained heap after delivery from +117.5 MB to
   +26.5 MB locally. The `DECORATE` round (`b1dec7d` to `a3c4330`) landed after
   that run and has not been measured on the runner yet: the next nightly run
   of the overhead benchmark on `master` is the first, and the ceiling's
   one-core startup (58.2 s at `8139271`) is the number to read.

10. **Docs for the agent and the collector.** Added 2026-10-05; parked for
    its own grill in a future session: where docs live (the READMEs, a
    `docs/` folder, or the `otherlode.dev` site), who they are for
    (quickstart, configuration reference, testkit guide, collector
    deployment, reading findings), and how much of it blocks `0.1.0`.

After release: naming polish (`this$0`, facade names, the demo printer),
the server's performance-only deferrals, gzip, a collector config file, agent-level redaction
(parked in the server's STATUS, item 18, with its trigger), and the
routine and OpenTelemetry edge cases in the entries below.

## TODO

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

### One-way doors settled before release: landed

Settled 2026-10-04 (checklist item 4). One Sonnet chunk and one commit each,
reviewed before it lands.

1. **Wire.** Rename `GENERATED_BY_NONE`, `UNREAD_SHAPE_NONE` and
   `ROUTINE_KIND_NONE` to `_UNSPECIFIED` and `UNREAD_SHAPE_SWITCH_LOWERING` to
   `UNREAD_SHAPE_STRING_SWITCH`; add `bool fields_stripped = 9` to
   `ResourceAttributes`. Then the collector: bump bindings, set the flag and
   log once per instance when redaction strips a field. Then the server:
   bump bindings, store the flag per run, keep flagged runs out of every
   finding and label them, and rename `never-initialised` /
   `never_initialised` to the `z` spelling.
2. **Agent options.** `endpoint` to `exportUrl`, refusing a query or
   fragment; OTel `service.version` as a fallback; the four internal field
   names aligned with their keys; README, demo and compose files updated.
3. **Testkit.** Its own result types and enums (nullable, no `NONE`),
   wire and protobuf shaded with NOTICE and licences; internal constructors
   with `@ConsistentCopyVisibility`; `@JvmOverloads`, `@JvmStatic` and a
   Java compile test; the seven internal queries; findings merged across
   instances with branch rows grouped by key; the agent-version check;
   `neverInitialized`; the renamed startup-timeout property;
   `explicitApi()` and the ABI dump in CI.
4. **Versioning.** `version=0.1.0-SNAPSHOT` in `gradle.properties`, applied
   to every subproject; the collector reports its version.

Landed so far:

- Chunk 1 on 2026-10-04 in all three repos: agent `731b10d` (schema
  published to the BSR by hand as `6678da8b`, since the renames are an
  intended break the workflow's registry check refuses), collector
  `61cfd15`, server `fa7250f`. The testkit rejects a stripped payload,
  since it expects the agent's payloads directly. The server excludes a
  stripped run in the one in-scope CTE and lists it only on the instances
  page; server ADR 0054's consequences hold what building it settled.
- Chunk 2 on 2026-10-04: agent `bfcd067`, site `ea29de9`.
  `flushInterval` keeps its name, since it holds a `Duration`; the other
  three fields follow their keys. The testkit's URL property is
  `exportUrl`.
- Chunk 4 on 2026-10-04: collector `fbbc7fb` (`version` const, logged at
  startup), agent `bf60db9` (`version=0.1.0-SNAPSHOT` in
  `gradle.properties`, which every project reads; the root script's
  version never reached subprojects, which were `unspecified`).
- Chunk 3a on 2026-10-04: agent `40f7bc2`. Two Opus reviews: a root site
  is renumbered to the merged branch indexes its root carries, and a
  declared edge's guard falls back to an instance that loaded the class,
  so the answer no longer depends on scan arrival order. The fallback
  became an identity match in the same change as the server's (server ADR
  0032, amended 2026-10-04; agent `352934e` and `4e093ae`, server
  `4b42ff2` and `95c615f`): the scan's own site key, role and case, never
  a bare index, and nothing for an outcome the scan cannot tell apart.
  Left as accepted: merged indexes can collide inside a root site when
  one testkit hears different builds, which the version check in 3b makes
  unlikely.
- Chunk 3b on 2026-10-04: agent `dc57547`. The testkit jar is the shadow
  jar; `verifyTestkitJar`, `apiCheck` (binary-compatibility-validator
  0.18.2, dump in `testkit/api/testkit.api`) and a compile-only Java
  source set run under `check`, and the `agentTest` suite runs against the
  shadow jar. Beyond the brief: the jar leaves out the `.proto` resources,
  and `OtherlodeExtension.collector()` is `@JvmStatic`. For item 6: the
  published testkit must be the shadow jar with kotlin-stdlib as its only
  dependency; the project's default variant is the plain, unshaded jar.
- Chunk 3 is split: 3a merges findings across instances, 3b hardens the
  surface (own types, shading, visibility, Java, ABI dump), so the dump
  is taken once, after 3a's field removals.

Parked from the same review, none a one-way door: a WARNING for an
`OTHERLODE_*` variable or `otherlode.*` property the agent does not know
(typos are silently ignored); option parsing and service-name detection
still run with `enabled=false`; no warning for `otelBridgeEnabled=true` with
`endpointsEnabled=false`.

### Runtime overhead: landed

Raised 2026-10-03, grilled 2026-10-04 with Luke. The design calls the woven
code close to nothing in several places and nothing measures it. `./gradlew
jmh` times `BranchSiteAnalyzer.analyze` only, which is transform time, and
that one number moved 27% on `demo` in a single chunk of the readable-findings
round. Unmeasured: the per-hit cost in the adopter's code, startup, heap, and
the cost of one flush, while the woven work keeps growing (the `<clinit>`
prelude, the omission loop in `$default`, a map lookup per request in endpoint
advice, the lambda-factory hook, a `getAllLoadedClasses()` walk every flush).

Settled:

- **Two kinds of number.** A macro run is what the README publishes, since it
  answers what an adopter asks before adding a `-javaagent` to production. A
  micro suite catches regressions and isolates contention, and stays
  internal: nanoseconds per probe say nothing about a service until you know
  how many probes a request crosses.
- **Macro subject.** `spring-petclinic-rest` with Postgres and k6, the setup
  OpenTelemetry's `benchmark-overhead` uses (fresh Postgres per run, 30s
  warmup, closed loop), so the number reads against theirs. `demo-spring` was
  rejected: about 15 probes, so a request crosses almost no woven code and the
  number would flatter. Two runs: the headline, `includePackages` covering
  PetClinic, and the ceiling, widened to `org.springframework`, which ADR 0033
  accepts as an adopter's choice. Three variants of each: no agent, the default
  agent, the agent with the static baseline. Metrics: OpenTelemetry's set
  (startup, allocation, heap, GC pauses, threads, mean and p95 latency,
  throughput, CPU, network) plus RSS and NMT class space, where ADR 0053's
  off-heap copy and ADR 0052's extra class-file read land. Startup is one row,
  time to first 200, not its own item. The agent exports to the
  `otherlode-collector` image at the same version, logging only, so the flush's
  encode and send are the real ones and its CPU is in the headline.
- **Where it lives.** `benchmark-overhead/`, a standalone Gradle build in this
  repo consuming the shaded jar, outside `settings.gradle.kts` and `check`.
- **Machine and report.** A manual workflow on `ubuntu-latest`, JDK 21 with
  G1, five runs per variant, the median with min and max. The README names
  the runner, JDK, agent version and commit, publishes both macro numbers and
  promises nothing. If the spread swamps the budget, move to a dedicated GCP
  VM.
- **Budget.** Under 3% on throughput and p95 for the headline run. A target,
  not a gate: a miss reopens the hot-path design (striping, per-thread arrays)
  as its own grill, reading the micro suite's contention run. The ceiling run
  has no budget.
- **Micro suite.** In the existing JMH source set. Four shapes, each woven
  through the real transformer against its unwoven bytes in one JVM through
  separate loaders, at one thread and one per core: an entry-only method, a
  branchy method with a loop and a `when`, a `$default` call with omissions,
  and an endpoint dispatch through `OtherlodeEndpoints.hit` with a registered
  entry. `-prof gc` on all four. The per-core run of the entry-only method is
  the contention measurement: every core writing one array element is true
  sharing, and JaCoCo's `arr[i] = true` precedent comes from test JVMs. JDK 25
  is a second leg here only; JDK 17, the floor since 2026-10-05, is not yet a leg.
- **CI.** Deterministic checks only, in `check`: zero allocation on the woven
  hot path, and no woven corpus method crossing 8000 bytes (the size guard's
  own test once it lands). The count of methods crossing 325 is reported,
  never gated. No timing fails a build.
- **Ceiling-run failures.** A broken app blocks 0.1.0 (startup failure, a
  wrong response, a `VerifyError`). Skipped classes and slow methods are
  published beside the ceiling number.
- **Before 0.1.0** (checklist item 7): the macro harness with both numbers in
  the README, the micro suite, the CI checks, and (moved before release on
  2026-10-04, Luke's rule being before release whenever there is a choice)
  one flush and the registry's heap timed on their own, a nightly schedule
  with results committed to the repo (changed 2026-10-05: results stay in the
  run), and the size guard below.

**Code size, measured.** Nothing in the weaver checks HotSpot's limits. Over
8000 bytes (`HugeMethodLimit`) a method is never JIT-compiled; over 325
(`FreqInlineSize`) a hot one is no longer inlined; over 65535 the class file
cannot be written. A scratch harness on 2026-10-04 ran the real transformer
(`OtherlodeInstrumentation.install` against a held transformer) over the five
benchmark corpora and read each method's code length before and after:

- A probe costs about 13 bytes: 13 to 15 for method entry (Advice adds a
  `goto; nop`), 24 to 28 per two-way site, 11 to 14 per switch case. Small
  methods roughly double; large ones grow 10 to 40%.
- Nothing crosses 8000 or 65535. The largest original method is 4410 bytes
  (demo `computeDependencyReport`, 5304 woven), the largest library one 2936
  (Ktor `respondStaticPath`). None starts above 6000.
- 168 methods cross 325, 103 in spring-webmvc, among them the per-request
  `RequestMappingHandlerAdapter.invokeHandlerMethod` (324 to 534). Most
  accessors cross C1's 35 and the trivial size of 6. The macro run measures
  what that costs.
- 65535 comes far sooner than 64 KB for branch-dense code: blocks of `if (x ==
  k) y++` fail at about 17 KB original. Through the real JVM path the failure
  is clean (`MethodTooLargeException`, a WARNING, `recordSkipped`, the class in
  the skipped list, the original bytes defined and run), but the whole class
  loses its probes for one method.

The size guard, before release: the analyser bounds each method's woven size
from its original length and its sites, with a margin for ASM widening jumps
past 32 KB, and when the bound crosses 8000 from below or nears 65535, drops
that method's branch probes through ADR 0025's drop mechanism, keeps its
METHOD probe and logs a WARNING naming it. No wire field: a missing branch
finding is a missed finding, not a false one, the trade ADR 0052 made for a
method whose sites do not pair. Recorded as an amendment to ADR 0052's
consequences when built.

The size guard landed on 2026-10-05, ADR 0052's amendment holding the rules.
Two reviews shaped it. The first build dropped a method's branch probes at
the 8000 edge even when its entry probe alone carried it past, losing
findings for nothing: such a method keeps its branch probes and is named.
The 65535 decision reads the received bytes as well as the class file, since
JaCoCo ahead of the agent grows a method the class file does not show, and a
re-weave re-checks it, leaving a method that no longer fits out of the branch
rewrite with its counts frozen. The 8000 decision reads the class file alone,
which a test proves by failing when received lengths leak into it. Over the
five corpora the bound is never below the woven length (slack 0 to 88 bytes),
and no method is guarded. Left as recorded: the widening allowance counts
every jump, so a method over about 45 KB that an earlier transformer grew can
lose branch probes it would have kept; the guard does not model `jsr`
inlining below version 51, or `<clinit>` and `$default` growth.

Landing order, one chunk and one commit each: (1) the micro suite; (2) the
CI checks; (3) `benchmark-overhead/` and its manual workflow; (4) the first
run, both numbers in the README, and the ceiling run's findings.

Chunk 1 landed: `HotPathBenchmark` in the `jmh` source set, run alone with
`./gradlew jmh -Potherlode.benchmark.include=HotPathBenchmark`, and
`HotPathWeavingTest` keeping its setup honest. `HotPathWeaver` defines one
fixture class twice from the same bytes in two child-first loaders, the second
copy through the transformer `OtherlodeInstrumentation.install` registers,
held instead of registered. The fixtures sit in `com.example.hotpath`, since
`TypeMatchPolicy` never includes `dev.otherlode.`. The review added `-prof gc`
to every run, which the brief had left out, and a test pinning the branchy
fixture at nine branch probes, so a lost switch probe cannot shrink what the
benchmark measures. A short run on 2026-10-04 (a 12-core laptop, half of them
efficiency cores, so not a number to publish): the woven probes allocate
nothing on every shape; the endpoint seam's lookup and hit allocate nothing;
the full Spring sequence allocates 24 bytes, the `List.of` key
`HandleMatchAdvice` builds per request by design. One thread per core showed
the endpoint dispatch at about 500 ns a call against 12 on one thread, the
largest contention effect in the suite; the runner's numbers decide whether it
matters.

Chunk 2 landed: two checks in `check`. `ProbeAllocationTest` runs in its own
`probeAllocationTest` task with `-XX:-DoEscapeAnalysis`, since the review
showed C2 removing a per-call `Long` box or `Object[]` inside the harness's
loop on JDK 21 and 25, so a probe change that boxed would have passed. It
calls each fixture's woven and unwoven copy in windows of 100,000 calls and
requires the fewest bytes any of five windows allocated to be zero (the JVM
allocates a few hundred bytes on the calling thread once as it moves a loop
between compiler tiers), and a control that boxes per call must read above
zero. C2 still removes an allocation whose every use folds away, which no
probe makes. The endpoint seam is outside the gate: JDK 21's
`AbstractImmutableList.equals` calls `iterator()` on the other list
(`ImmutableCollections.java:311`), so a per-request key equal to the
registered one allocates, and the JMH profiler reports that instead.
`CodeSizeLimitsTest` weaves all five corpora through the real transformer in
about 4 s, fails on any method weaving pushes past 8000 bytes, any class
skipped as too large, any method missing from a woven class, or a corpus of
which under 75% wove, checks the checker on synthetic classes crossing 8000
and 65535, and writes the crossings of 325 bytes to
`build/reports/code-size/limits.txt` (18, 0, 5, 103, 42, as the scratch count
found). The review found the too-large check dead as first written: the skip
records ASM's message ("Method too large: ..."), which never names
`MethodTooLargeException`. At least 90% of the classes the transformer wove or
failed on must weave; a class is left out of that share only when it fails
naming a type its classpath lacks and also fails to load in a fresh loader for
want of a class, and at most 20% of a corpus may be left out, so a wrong
classpath cannot excuse everything. 58 spring-webmvc classes are left out that
way.

Chunk 3 landed: `benchmark-overhead/`, a standalone Gradle build run as
`./gradlew -p benchmark-overhead test -PagentJar=... -PcollectorDir=...
-Pconfig=headline|ceiling`, and the manual `benchmark-overhead` workflow, which
runs both configs on `ubuntu-latest` and uploads the results. Settled while
building, past what the grill decided: a 150 s warmup with a throwaway JFR
recording, then a 180 s window, so three default flushes fall in it; the heap
fixed at 1 GiB, PetClinic limited to 2 CPUs and 2 GiB, variant order rotated
each repeat with 6 repeats by default so each variant takes each position
equally often, Postgres 16.3, k6 2.3.0 with 5 virtual users; class space from
`jcmd VM.metaspace` rather than Native Memory Tracking, which would slow every
variant; CPU from the container's cgroup `cpu.stat`, since JFR's `jdk.CPULoad`
is a share of the host; the collector pinned to `master` until 0.1.0 publishes
an image. "Slow methods" beside the ceiling number come from
`CodeSizeLimitsTest`'s report, which covers spring-webmvc, not from the macro
run, which cannot see method sizes.

The review changed what the numbers mean. k6 tagged each request with its URL,
so every id started a metric series (400,000 in a 30 s window), and k6 ate the
runner's CPU: a name tag per request and the `url` system tag off doubled
local throughput. In a closed loop the container sits at its CPU limit and a
faster variant allocates more in total, so the comparable figures are per
request (CPU ms, KiB allocated, GC pause per 1000). A window counts only if its
last third served within 10% of its first, either way; the first local runs,
with a 10 s warmup, climbed fivefold through the window and showed the agent
25% faster than no agent. Delta batches go out empty as a heartbeat, so
an agent run must also show probes and endpoints in its manifests, no disabled
endpoint module and no agent ERROR line, which Spring Boot's logging prints in
its own format once it starts. The skipped count comes from the manifests: a
class the type matcher turns away is recorded without a log line. The JFR recording stops as k6
finishes rather than at exit, where it had taken in the agent's shutdown flush,
and every variant gets the agent jar copied in, so the copy costs each
variant's startup alike. The summary marks a change inside `none`'s own spread
and warns about a run whose PetClinic averaged under 1.9 cores: CPU per request
and throughput are one measurement while PetClinic is held at its limit, and
not even that when it is not.

A local headline run on 2026-10-04 (one repeat, 60 s warmup and window, a
laptop, so indicative only, and confounded by order: none, agent and
agent-baseline ran in that order and throughput fell in that order, while the
two agent variants should match in a window, so the noise floor is about 4%):
the agent at -2.4% throughput, +2.3% p95, +8% RSS; the baseline variant at
-6.2% and +8.1%.
Startup was 4.1 s without the agent and 6.8 s with it, the same +2.7 s every
local run has shown, in Spring's own "process running for" too: the agent's
startup cost on a Spring Boot 4 app with narrow include rules, outside the
budget, which covers throughput and p95. Its cause is not yet looked at. A
ceiling run on the same laptop, before the review's fixes, took startup from
3.8 s to 17.4 s; the ceiling has not run on the fixed harness.

The first workflow run (2026-10-04, run 37230002817) failed both configs on
the steadiness rule, as it should: on the 4-vCPU runner `none`'s throughput
climbed from 108 to 200 iterations a second over the first 45 s of the window
after a 60 s warmup, a 30% drift. The default warmup went to 150 s.

Chunk 4 landed on 2026-10-05. Two harness changes first. Pinning: each
container gets its own cpuset from Docker's CPU count (PetClinic n/4 cores,
at most 2, Postgres and the collector n/4, k6 the rest, four k6 users per
PetClinic core), and a run whose PetClinic averaged under 90% of its pinned
cores is invalid; on the runner PetClinic averaged 0.97 of its one core.
Warmup: on one core the compiler threads share the application's core, and
150 s left the window rising 16% and 31% (run 37256025629), so the warmup
runs in 30 s slices until the last two each match the one before within 3%,
90 s to 600 s, and the job's timeout rose to 360 minutes; warmups ended
steady after 240 to 330 s. Run 37256902161 on the fixed agent (`7f95d26`),
every run valid and no class skipped in either config: headline 1,489
probes, throughput -0.5% (noise), CPU per request +0.7%, RSS +84 MiB,
startup 13.6 s to 23.9 s; ceiling 100,302 probes, throughput -4.9%, CPU per
request +5.4%, p99 +5.2%, GC pause per 1000 requests +20%, RSS +306 MiB,
startup 15.2 s to 64.0 s. The README carries the table. The headline budget
(throughput and p95 within noise) holds. Startup is the cost that stands out,
amplified on one core, and is the entry "The agent's startup and heap: landed" below.

One flush timed on its own (2026-10-05): `FlushBenchmark` runs the real
`ExportScheduler.flush()` over a registry filled from the real transformer's
registrations, encoding every payload and sending nothing. At the five corpora
together (1,195 classes, 22,597 probes): a steady flush with a tenth of probes
changed 0.16 ms and 650 KB allocated, an empty heartbeat 0.04 ms and 200 KB, the
first flush with the whole manifest 22 ms and 40 MB; at the demo's 2,623 probes
0.02, 0.01 and 1.4 ms. Allocation is dominated by copying each count array, about
8 bytes a probe a flush. The loaded-class sweep, timed against the benchmark
JVM's own 4,900 classes, takes 0.5 ms. The first flush scales with the manifest:
the ceiling's 100,302 probes would take about 100 ms and 180 MB once, which
belongs to the heap investigation.

The workflow runs nightly on `master` (03:17 UTC, default inputs) as well as
by hand. Changed from the grill on 2026-10-05 with Luke: results live in the
run (job summary, and artifacts kept 90 days) and are not committed to the
repository. Committing them to a results branch is deferred until a trend view
across months is wanted, and adds nothing that is hard to add later.

**Found by the sweep, closed by ADRs 0058 and 0059 (no class skipped in the
2026-10-05 runs):**

- A class that names an absent optional type is skipped even when the JVM
  loads it. ByteBuddy resolves field and signature types while validating the
  rebased class (`InstrumentedType.Default.validated()`), which the JVM leaves
  until use. 18 spring-webmvc classes load and initialise without their
  optional dependency and are still skipped: the PDF, feed, FreeMarker, Groovy
  and Jackson 2 views, several JSP tag helpers, `LiteWebJarsResourceResolver`.
  The skip is reported, so nothing reads as dead, but an adopter class with an
  optional dependency in a signature loses its probes. The sweep's report
  lists them.
- An annotation type with a `<clinit>` is skipped (2 in the corpora, one in
  spring-webmvc and one in ktor-server-core): `TypeMatchPolicy.unsafeAnnotation`
  checks only `ElementType.TYPE`, while ByteBuddy also accepts
  `ANNOTATION_TYPE` on an annotation type (`InstrumentedType.java:1759-1760`
  in byte-buddy 1.18.12's sources).

### Probe arrays, validation and placeholders: landed

Grilled on 2026-10-04 with Luke, from the overhead ceiling run (PetClinic
REST on Spring Boot 4.1.1, `includePackages=org.springframework`), which wove
4150 classes and skipped 312. ADRs 0058, 0059 and 0060, with 0004 and 0007
amended; `CONTEXT.md` drops "statically unsafe class". The rule behind all of
it: skip only what cannot be woven, and name every skip.

**A woven class could crash where the original ran.** Probes read the class's
`$otherlodeProbeCounts` field, which the class's own `<clinit>` fills. When a
supertype's `<clinit>` runs the class's code first (JVMS 5.5), the field is
null and the probe throws `NullPointerException`, leaving the class erroneous
for the JVM's life. Reproduced with the agent jar: a Java superclass constant
holding a subclass instance, an interface constant holding an anonymous
implementation, a Kotlin sealed class or sealed interface whose companion
holds a subtype instance, an enum constant body initialised by reflection.
ADR 0060: from class-file version 55 every probe loads the array as a
dynamic constant bootstrapped from `OtherlodeProbeArrays`, and the class gets
no field, prelude or added method; below 55 every probe calls a private
accessor that falls back to `resolve` while the field is null. JaCoCo 0.8.13
splits the same way. A sentinel array is impossible (a static field is null
until `<clinit>`, JVMS 5.4.2), and skipping the count would make an enum
body's constructor a false never-hit.

**The 312 skips:**

- 166 were annotation types turned away by the agent's copy of ByteBuddy's
  annotation check, which copied half the rule (ByteBuddy also accepts an
  `ANNOTATION_TYPE` annotation on an annotation type,
  `InstrumentedType.java:1759-1760`), with no log line. None had anything to
  probe. The static baseline put every annotation type in its statically
  unsafe bucket for the same reason.
- 90 were spring-data-jpa classes compiled by ajc 1.9.25.1, which copies a
  `TYPE_USE` annotation in front of a field or return type (jspecify's
  `@Nullable`) into the declaration attribute; validation rejects the copy.
  javac, ecj and kotlinc never do this.
- 2 failed validation resolving an anonymous class's receiver type, and 3
  named an absent type only inside validation.
- 51 named an absent optional dependency (Reactor, Querydsl,
  `kotlin.reflect`) in a signature, failing while ByteBuddy described the
  class.

ADR 0058: `TypeValidation.DISABLED`, as Elastic's agent runs. Every check it
made fired on the adopter's bytes, never on the agent's, at every class-file
version from 45 to 69; turning it off recovers 95 PetClinic classes and every
`@file:JvmName` class. The copied check, the baseline's bucket and the wire's
`statically_unsafe_classes` go, the field reserved, with the collector and
server in lockstep. A verifier test in `check` replaces the guard: every
corpus class and a fixture matrix of versions 45 to 69 are woven, defined
and initialised, and a woven class may fail only where its unwoven twin
fails. ASM's `JSRInlinerAdapter` runs first on class files below 50, since
subroutine code failed whatever the setting (the branch tier computes
frames). Every remaining skip logs one WARNING. ADR 0059: the AgentBuilder's
pool describes a missing type as a placeholder (public, extends `Object`,
with the type-variable count its references use), and a class whose own
supertype is missing is still refused; a prototype wove all 51 to classes
that load exactly as their twins do, and 1,772 others to identical bytes.

Also settled: before release, a JFR profile of the agent's startup on the
headline config (+2.7 s over 4.1 s with about 100 classes woven) and a heap
histogram of the ceiling config after first delivery (about 1.5 KB of heap
per probe), each reported as findings, with any design change grilled.

Landing order, one chunk and one commit each: (1) probe arrays, ADR 0060,
with a forked-JVM test of every crashing shape at versions 52 and 66 and a
redefinition test of both forms; (2) validation off, the copied check
removed, skips logged alike, the subroutine inliner and the verifier test;
(3) the wire field removed and reserved, then `otherlode-collector`'s
bindings, then `otherlode-server`'s bucket; (4) placeholders, with the
supertype guard and the byte-identical regression test; then the overhead
entry's moved items and its chunk 4, rerun on the fixed agent with each
container pinned to its own cores, since on the 4-vCPU runner PetClinic
averaged 1.6 of its 2 CPUs and throughput measured k6 instead.

Chunk 1 landed: probes reach their array through `ProbeArrayForm`. From
class-file version 55 every probe site loads a dynamic constant bootstrapped by
`OtherlodeProbeArrays.probeArray`, one constant-pool entry per class, and the
class gets nothing else; a class with its own `<clinit>` gets an ordinary entry
probe there. Below 55 the field and prelude stay and every site calls the
private accessor. The form comes from the received bytes' version and is stored
in the plan, so a re-weave keeps it. `ProbeArrayWindowTest` forks the agent jar
over six shapes (superclass constant, interface constant, a superclass calling a
subclass's static method with a branch, an enum body by `Class.forName`, a
Kotlin sealed class and sealed interface) at versions 52 and 65; all twelve
crashed on `0cb8706` and pass, with the window's counts landing. Review added
the refusal for a version 55 class whose class file changed, which the chunk
had left accepting unwoven bytes (Luke chose refusal, ADR 0053 amended): the
bytes handed back carry a `public static final` marker field, public and final
because a private one is illegal on an interface, where ByteBuddy rejected it
and the JVM took the unwoven bytes after all, a case the second review caught
and a test now pins along with a HotSwap-shaped `redefineClasses`. Not covered
and pre-existing: an adopter class in a named module must read the boot
loader's unnamed module to reach the holder, as the old prelude did; nothing
adds that edge for adopter modules.

Chunk 2 landed: type validation is off for the method tier; the endpoint
modules and the lambda-factory hook keep it, since `DECORATE` never reaches
the failing check and the class-version guard is the only one on their
advice. The copied annotation check is gone from the type matcher and the
scanner, whose statically unsafe list stays on the wire, empty, until chunk 3.
ASM's `JSRInlinerAdapter` (Byte Buddy ships no tree or inliner) comes from a
new `:asm-subroutines` module relocating asm-commons and asm-tree 9.10.1, Byte
Buddy's own ASM release, into its ASM package; it runs below class-file
version 51, where `jsr` is legal (JVMS 4.9.1), on the analysis bytes and as the
outermost visitor of the rewrite. `WovenClassVerificationTest` defines and
initialises every woven corpus class beside its unwoven twin and a fixture
matrix at versions 45 to 69. The review found a re-weave writing a dynamic
constant into bytes another tool downgraded to version 52, a
`ClassFormatError` nobody logged: a re-weave whose arriving version needs a
different probe form is refused. It also tightened the verifier (a woven
`VerifyError` or `ClassFormatError` always fails it, two failures must share a
root type, every class that fails to weave must name a missing type, per-corpus
floors just under the measured counts), moved the inliner's cut-off from 50 to
51, added a branchless old class and a used annotation type to the matrix, and
restored a test that a class which failed to weave is left alone on
retransformation. A review harness over junit 3.8.1 and xerces 2.11 wove all
33 of their subroutine classes.

Chunk 3 landed in all three repos: agent `e15268a` reserves
`StaticBaseline.statically_unsafe_classes` (3) and drops
`StaticallyUnsafeClass`, published to the BSR by hand as `582df6f7`, the
intended break the workflow's registry check refuses; collector `083a65d`
moves its bindings and drops the log field; server `e842933` stops writing
bucket 1, and its migration 0010 deletes those rows (all test data, Luke
said) and adds a check constraint refusing the code, with `bucketString`
reading an unknown code as unknown.

Chunk 4 landed on 2026-10-05, after ADR 0061 so placeholders never enter a
frame: the method tier's `AgentBuilder` pool describes a type it cannot locate
as a placeholder (a public class extending `Object` with the type-variable
count its references use, through a wrapped cache provider, a ThreadLocal pool
per transform). A class any of whose supertypes is missing or unreadable is
refused with one WARNING. A first build put frame guards around ASM's frame
computation; review found a hole in them (a merge walks supertypes the guard
never saw) and a class-file read per class load, and they went with 0061.
Review of the landed pool then found ByteBuddy writing the class header from
its description, which predates this chunk too: a local class's simple name
changed (`Outer$Local$1` read "Local$1"; Scala and Ktor corpus classes), a
member class described as a placeholder lost its outer class (reflection threw
`IncompatibleClassChangeError`), the final bit dropped on some local classes,
and a public class inheriting from a package-private one gained a public
synthetic bridge. `ClassHeaderRestorer` writes every woven class's header
(flags, signature, InnerClasses in order, EnclosingMethod, nest and permitted
attributes) from the received bytes, in ASM's event order, on the weave,
re-weave and nothing-to-probe paths, and the method tier builds no visibility
bridge. About 152 corpus classes' bytes change from the committed agent's, all
header. `WovenClassVerificationTest` compares every woven corpus class with its
class file apart from code and the agent's own members, and fails with each of
those fixes reverted except the bridge, which no corpus class exercises and a
fixture pins. spring-webmvc weaves 26 more classes (472), 46 are refused for
a missing supertype, and spring-core's `PropagationContextElement$ReactorDelegate`,
`TagWriter$SafeWriter` and a class merging two types a loader defines from memory
weave and behave as their twins. A review harness under the agent jar showed
reflection on nested, local, anonymous, sealed, record and enum classes
identical with and without the agent, and after retransformation.

### Keep each method's original frames instead of recomputing them: landed

Grilled and built on 2026-10-05, ADR 0061 (amending 0006, 0052 and 0058),
Luke taking the main session's recommendations after the research. The branch
tier reads with expanded frames, keeps every frame the received bytes carry,
and writes one at each label it inserts from an ASM `AnalyzerAdapter`'s state
at the jump, its operands popped; where an inserted fall-through label lands on
an instruction with its own frame, the class file's wins (the opposite gave
`VerifyError`s in the research); max stack grows by six. The method tier's
class writer (`FrameRefusingClassWriter`) throws from `getCommonSuperClass`,
so nothing recomputes a frame; a frame the agent cannot derive skips the
class. The research prototype and the chunk passed every suite, including
real JaCoCo in both orders; `FramesShapesTest` compiles jumps, switches,
loops, handlers, `synchronized`, wide values, uninitialised `NEW` and
`UNINITIALIZED_THIS` at a jump, and a method pushed onto `GOTO_W`, at releases
8 to 21 and version 50 with and without frames, and checks the woven version
50 bytes relabelled 51, where the JVM cannot fall back to inference. The
verifier test fails a woven class that runs where its twin fails. The review
found no defect in the frame logic; it corrected comments and ADR wording
(where a frame cannot be derived, which frames still appear below 50) and
made the `asm-subroutines` jar track its exclude patterns. `ReactorDelegate`
behaves as its twin only once 0059's pool lands; on this tree it names a
missing type and is skipped, and its test will require a weave then.

Brought forward on 2026-10-05, Luke's call, to unblock ADR 0059: the
placeholder pool's frame guards had a hole (a merge walks supertypes the guard
never sees) and cost a class-file read per class load, and every guard was a
symptom of recomputed frames. The placeholder chunk was parked in a stash meanwhile and
landed after this without its frame guards (chunk 4, in the entry "Probe
arrays, validation and placeholders").

Raised 2026-10-05 while landing ADR 0059's amendment, as the wider fix for a
class of problem that amendment only guards against. The branch tier makes ASM
recompute every stack map frame of a class it rewrites (`COMPUTE_FRAMES`), and
a recomputed frame can differ from the class file's in what the verifier has to
load:

- A class file's frame can name a type the verifier must load to check an
  assignment, as spring-core's `PropagationContextElement$ReactorDelegate`
  types a local `ContextView` (Reactor's). Without Reactor it fails
  verification with `NoClassDefFoundError`; ASM types the local `Context`, the
  check goes, and the woven class defines and initialises. A framework probing
  an optional integration by catching `NoClassDefFoundError` would read it as
  present.
- ASM merges types through the type pool, so a type the pool cannot see (a
  placeholder, or a class a loader defines from memory) widens a merge to
  `Object`, which turned a class that ran unwoven into a `VerifyError`.

ADR 0059's amendment refuses a class whose weave would merge a placeholder or
whose own frames name one, which is correct but only covers classes woven with
placeholders: a class woven without any, whose missing type appears only in its
code, could still load woven where it fails unwoven. Not yet seen in a corpus.

The wider fix: keep each method's frames as the compiler wrote them, as JaCoCo
does, so the JVM verifies the woven class against the same frames and loads and
fails exactly what the unwoven class does. It looks workable: the probes only
push and pop the operand stack and add no local, so the only frames to supply
are at the labels the branch tier inserts on branch edges, where the frame is
the original jump target's, already in the class file; ASM can hand every frame
expanded, and an `AnalyzerAdapter`-style tracker covers fall-through edges.
ByteBuddy's `Advice` already handles frames without recomputing them, and a
class below version 50 has none to keep (the subroutine inliner covers those).
It would also drop frame computation from transform time, one of the things
that reads supertypes' class files through Spring Boot's nested-jar loader
(the 2026-10-04 startup investigation, since removed, counted about 111k reads in
the ceiling run). The cost is the branch rewriter emitting a correct frame at
every inserted label, for both jump polarities and switches, beside Advice's own
frame handling in the same method; `WovenClassVerificationTest` (ADR 0058) is
the net for getting it wrong. If adopted, ADR 0059's frame-merge hook and
second frame guard, and its strict pool for loaders without class files, become
redundant; the placeholders, the supertype guard and the signature restore
stay. To settle in the grill: whether to adopt it before release (Luke's rule
says yes unless something blocks), how to treat class-file version 50 (frames
optional, type-inference fallback), and what happens to a method whose frames
an earlier agent already rewrote.

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

### The agent's startup and heap: landed

Measured 2026-10-04 on PetClinic REST, re-measured at `c4f91d7` on 2026-10-05
(Corretto 21.0.5 with CDS, a 12-core Mac, medians of five runs, async-profiler
4.1; a live collector and live heap after two full GCs for the heap), and
grilled the same day with Luke. Every item is before 0.1.0 (checklist item 9).

**Startup at `c4f91d7`.** "Process running for" goes from 3.24 s to 5.48 s with
the headline config and to 13.84 s with the ceiling (4,208 classes, 98,546
probes). ADRs 0058 to 0061 took 1.25 s off the ceiling, nearly all of it
validation, and nothing off the headline. Of the headline's +2.24 s, about
1.5 s is the JAX-RS module's type matcher, which reads every one of the ~19.6k
classes the JVM loads and walks their supertypes (25.9k class-file reads) in
an app with no JAX-RS, about 0.35 s is weaving PetClinic, and about 0.36 s is
`premain` (building the transformer 125 ms, the `HttpClient` 109 ms). At the
ceiling, locating class files is 51% of the 9.7 s of transform time: 92k reads
through Spring Boot's nested-jar loader at about 54 microseconds each, about
78k of them repeats of a supertype or referenced type. The largest readers are
the JAX-RS matcher (25.9k), ByteBuddy's visibility check in
`MethodRegistry.prepare` (24.6k, 1.56 s, which checks every inherited method's
return and parameter types), the analyser's `crossClassLookup` (11.1k), ADR
0059's supertype guard (10.4k), the rebase builder (8.3k), and `Advice.to`
re-reading the advice class on every weave (4.1k). `BranchKeys.digest` is
0.4 s, mostly `"%02x".format`. On the runner's single pinned core the headline
went 13.6 s to 23.9 s and the ceiling 15.2 s to 64.0 s.

**Heap at `c4f91d7`, ceiling, after delivery.** +117.5 MB live over no agent.
`ProbeRegistry` 64.6 MB, of which `ClassEntry.probes` is 60.3 MB (612 B a
probe; strings with many duplicates, tens of thousands of empty and
single-element lists), and after delivery only each probe's `kind` is read.
`OtherlodeInstrumentation.tableCaches` 33.9 MB, read only by a first weave's
analysis and held for the loader's life. `WovenClasses` 4.7 MB, `DependencyRegistry`
1.3 MB. Native: G1 commits +178 MB it grew to for transform-time garbage and
keeps, the JVM's copy of woven bytes +20 MB. The first flush at the ceiling
builds every manifest chunk before sending, about 180 MB and 100 ms, once.

Settled:

- **Target.** A startup target for the headline's time to first 200 on the
  runner, not a gate, set as a percentage once the work below lands (starting
  point: within 25% of no agent); a miss reopens the design. No target for the
  ceiling, none for heap. The nightly harness watches both.
- **JAX-RS gate.** A per-loader gate on `jakarta/ws/rs/Path.class` or
  `javax/ws/rs/Path.class` being present, a cached `getResource` in a weak
  map, ahead of every parsing check, as OpenTelemetry's `hasClassesNamed`
  does; the bootstrap and platform loaders are ignored for this module only
  (the JDK `HttpServer` module needs them); a per-loader bounded cache of the
  supertype walk's answer. Include rules still do not apply (ADR 0017). A line
  in ADR 0017's consequences.
- **`BranchKeys.digest`.** A 16-entry hex lookup table, the derivation and
  the `string` wire type unchanged, today's outputs pinned by a test first.
  Raw 16-byte keys on the wire were considered, since the wire is still free
  to change, and set aside: about 1.6 MB a ceiling manifest, against a
  three-repo lockstep.
- **`HttpClient`.** One client shared by both exporters, built lazily on the
  export thread at the first send. Its TLS set-up stays, off the startup path;
  no hand-rolled HTTP client.
- **Dependency listing.** Started on the exporter thread at the first flush
  rather than from `premain`, so its 0.9 s of CPU never competes with startup
  on a one- or two-CPU host.
- **Lambda-factory hook.** Stays synchronous in `premain` (26 ms), since moving
  it widens ADR 0035's miss window.
- **Declared methods only.** The method tier's ByteBuddy uses
  `MethodGraph.Compiler.ForDeclaredMethods`, since it weaves declared methods
  only and builds no visibility bridge. Conditional on a check against
  byte-buddy 1.18.12's `MethodRegistry` and `MethodGraph` sources and
  OpenTelemetry's `AgentInstaller` that nothing in a rebase needs inherited
  methods, `WovenClassVerificationTest`, and ADR 0059's byte-identical
  regression over the corpora; a dependency found goes back to Luke.
- **Class-file byte cache.** One JVM-wide LRU of class-file bytes, negative
  answers included, keyed by weak loader and name, capped at 16 MB, in front
  of every locator the agent uses (the placeholder pool, `describeClassFile`,
  `crossClassLookup`, the supertype guard, the JAX-RS walk). Parsed
  descriptions are not cached: they are heavy, and locating is 51% of
  transform time against parsing's 8%. Two reads bypass it, both in its KDoc
  and tested: a class's own class file for its first analysis, and the
  re-weave's class-file comparison (ADR 0053), where a stale copy would let a
  HotSwapped class through on the old plan. `Advice` is built once, not per
  weave.
- **Releasing transform-time caches.** The flush thread clears a loader's
  table cache and byte-cache entries when the loader had no first weave since
  the previous flush. A later load refills them, at a cost in time only.
- **Delivered probe metadata.** Once a class's manifest is delivered, the
  registry keeps a compact kind array for it and drops its `ProbeMeta` list.
  The manifest is send-once by design: a collector that loses an instance's
  manifest cannot get it back without that instance restarting, which an
  amendment to the registry ADR records. `ProbeRegistry.manifest(resource)`,
  test-only, goes; its tests read delivered manifests.
- **Manifest chunks one at a time.** Built, encoded, sent and advanced one
  chunk at a time, so the first flush's peak is one chunk and each delivered
  chunk's metadata is collectable before the next is built. The first failure
  still stops the send.
- **Interning and list sizing.** Names interned with `String.intern()` and
  empty or single lists stored as `emptyList()`/`listOf(x)` wherever metadata
  is built (probe metadata, call edges, referenced classes, tables), since the
  peak before the first flush is what G1 sizes itself to.
- **Kept as is.** `WovenClasses` plans (ADR 0053 needs them).
- **Allocation.** One async-profiler allocation pass once the rest has landed;
  local top allocators fixed in this work, anything structural back to Luke.
  No target: how much G1 commits is the adopter's GC settings.
- **`check`.** The JAX-RS gate rejects classes on a loader without JAX-RS
  reading no class file (a counting locator); no `ProbeMeta` survives a
  delivered manifest; the key-output pin. No timing gates a build.
- **Records.** After-numbers go in this entry; both investigation docs are
  removed when the work lands; their scripts move into `benchmark-overhead/`
  as a manual profiling kit, and `benchmark-overhead/README.md`'s pointer to
  `docs/investigations/` follows them. The design's registry section gains
  the send-once property. No wire change, so `otherlode-collector` and
  `otherlode-server` do not move.

Landing order, one Sonnet chunk and one commit each, reviewed in the main
session, a large fix re-reviewed by Opus until reviewers and implementers are
both happy: (1) the JAX-RS gate; (2) the digest, the lazy `HttpClient`, the
deferred listing; (3) declared methods only, source check first; (4) the byte
cache and `Advice` built once; (5) releasing caches when quiet; (6) delivered
metadata, chunks one at a time, interning; (7) the allocation pass; (8)
re-measure locally and on the runner, Luke sets the startup target, the
scripts move, the docs go, this entry and the design updated.

Chunk 1 landed: `EndpointModule` gained `classLoaderMatcher()` and a loader-aware
`typeMatcher(classLoader)`, both defaulting to the old behaviour, and the pipeline asks the
loader first through one `RawMatcher`. JAX-RS gates on its `Path` resource per loader and caches
its supertype walk per loader (LRU of 8192 names; an answer that depended on the cycle guard is
not cached). Review made the form that is not told the loader cache nothing, since the first cut
shared one cache across every caller of it and could have mixed two loaders' names. ADR 0017
amended.

Chunk 2 landed: `BranchKeys.digest` hexes through a lookup table, its output pinned by
`BranchKeysPinTest` from the unchanged code; one `HttpClient`, a `Lazy` shared by both exporters,
built by the first send; the dependency listing is a `DependencyListingRun` that runs once, at
the top of the first flush (before its sweep, so a listed jar registers as a startup dependency
rather than one found at load) or from the baseline reference filter before it waits, whichever
comes first. A process whose first flush is its shutdown flush lists inside the 10 s shutdown
budget; a flat or fat jar's listing took 0.2 to 1 s in the measurements.

Chunk 3 landed: the method tier's ByteBuddy uses `MethodGraph.Compiler.ForDeclaredMethods`, as
OpenTelemetry's agent does (`AgentInstaller.java:124-127`, v2.32.0). An Opus check of byte-buddy
1.18.12's sources found nothing in a rebase that needs inherited methods for this agent: the graph
feeds `Advice`'s name-and-descriptor lookup, the rebase resolver (empty here), visibility bridges
(`NEVER`) and `Implementation.Target`, which the agent never reads. It also found a bug the switch
fixes: the default graph merges declared methods that differ only in return type into one node,
which `Advice` wraps once, so the others registered METHOD probes that never counted, a false
never-hit (scalac emits such overloads, `StringOps.map`, `Function.uncurried`); and Scala's
`MapView*` classes failed to weave on a wildcard the default graph cannot erase.
`DeclaredMethodsWeavingTest` pins both, failing on `f264cee`. A third shape the experiment saw only
in scala-library 3.8.1 (`ArraySeq$ofUnit.update` represented by its abstract supertype method) did
not reproduce with a minimal fixture or with 2.13's `ArraySeq$ofUnit`, so it has no test. Over the
five benchmark corpora both compilers wove byte-identical classes and failed the same ones (46
spring-webmvc classes refused for a missing supertype, ADR 0059), so the corpora carry none of the
shapes; in the experiment Spring 6.2.19's jars went from 46k class-file reads to 30k.

Chunk 4 landed: `ClassFileByteCache`, one per agent and shared by both tiers, caches class-file
bytes and misses by weak loader and name, LRU under 16 MB. It sits behind the method tier's and
the endpoint tier's `LocationStrategy` (so ByteBuddy's pool, the placeholder pool and ADR 0059's
supertype guard read through it), `describeClassFile` and `crossClassLookup`. A class's own class
file for its first analysis and the re-weave comparison of ADR 0053 read uncached, and a test
primes the cache with a class's old file before a HotSwap-shaped redefinition to pin the refusal.
`Advice` takes its advice classes from memory, described once. Class-file reads through the loader
over the corpora fell from 715 to 236 (demo), 5,858 to 1,442 (spring-webmvc), 5,140 to 1,048
(ktor-server-core) and 2,375 to 649 (scala); the woven bytes hash identically before and after.

Chunk 5 landed: after each flush's sends (not the shutdown flush), the flush thread calls
`OtherlodeInstrumentation.releaseQuietCaches()`, which removes the table cache and drops the
byte-cache entries of every loader that was quiet since the previous call. Widened from the
grilled wording ("no first weave"): a loader is active if it asked for its table cache or touched
the byte cache from either tier, since the JAX-RS walk reads through the byte cache on loaders
where the method tier weaves nothing, and those would otherwise be dropped and refilled on every
flush. The two locks are taken one after the other, never nested; a failure is logged once and
never stops a flush.

Chunk 6a landed: a `ClassEntry` keeps a `kinds` byte array for the delta path and holds the rest
of its metadata (probe locations, supertypes, references, source file, body kind, source name,
Kotlin kind) in one volatile `ManifestData` that `advanceManifestBaseline` nulls. ADR 0008 amended:
the manifest is send-once. `computeManifestDeltas` yields chunks lazily and the scheduler sends and
advances each before building the next, re-checking after each yield for a class delivered in
between; riders still pack first-fit, taken chunk by chunk, which places each where the eager code
did (pinned on four cap and endpoint combinations, 25 checked once). Changed from Q9:
`ProbeRegistry.manifest(resource)` stays as the test helper it was (about 240 test call sites, none
delivering), covering classes not yet delivered and throwing on a delivered one. Over the five
corpora (22,597 probes) the registry retained 11.0 MB after delivery before and 0.9 MB after.

Chunk 6b landed: `MetadataCompaction.kt` holds `interned()` (`String.intern()`, no strong map) and
`rightSized()`, applied where `ProbeMeta`, call edges, references, supertypes, kept branch sites
and the table caches' `MethodTable`s are built. Interned: class, method and descriptor names,
generic signatures, parameter names, source files. Left alone: branch and site keys and condition
fingerprints (unique), condition text (a possible follow-up). The five corpora's registrations
retained 29.7 MB before and 21.8 MB after (one run each).

The allocation pass (2026-10-05, async-profiler `event=alloc` on the ceiling config at `1e1746f`,
JVM start to "Started"): the agent allocates about 6.5 GB of the 8.0 GB total (1.4 GB without the
agent), with 75 to 78 young GCs against 21. Class-file reads are 1.41 GB, about 106 KB of garbage
each through Boot's nested-jar loader (the byte cache holds: 46k lookups, 659 repeats from its cap);
ByteBuddy's per-transform pool parses 6,472 distinct types 39,715 times, 1.42 GB, `java.*` alone
11.8k parses of 275 types; the rebase's `prepare`, `represent` and `toTypeWriter` with their parses
are about 1.7 GB. Local fixes, about 600 MB, are chunk 7a. Grilled the same day with Luke:

- **JDK types parsed once (Q21).** One JVM-wide pool for bootstrap-loader types, about 275 entries,
  which cannot go stale and never meets a placeholder. The per-loader cache of parsed descriptions
  stays rejected (Q17) unless chunk 8's numbers miss the target.
- **A woven class's own class file from its code source (Q22).** Read as the `ProtectionDomain`'s
  code-source location plus the class's path, which names the copy the JVM defined, falling back to
  the loader when absent or unreadable; about 493 MB at the ceiling. Package hints for cross-class
  reads were rejected: a split package would make a hint read another jar's copy, and wrong call
  edges for a duplicated class would be a silent error.
- **`DECORATE` and an ASM entry probe (Q23).** Their own grill, before 0.1.0; the entry below.

Landing order amended: (7a) local allocation fixes; (7b) the JDK-type pool and code-source reads;
(7c) the handler warm-up and the JDK pool's release; then (8) as above.

Chunk 7a landed: one per-transform list of probed methods replaces repeated `MethodDescription`
descriptor building; `BranchKeys` feeds a thread-local digest the same bytes part by part (pinned);
`ReferenceCollector` cuts names out of descriptors without `Type` objects and skips one of its last
eight descriptors by identity; method tables detect Scala in their own visitor and are built
compact directly; `ScalaClassDetector` walks the class file without allocating, falling back to
ASM; `isUnderPrefix` and an internal-name form need no concatenation; the endpoint raw matcher
builds each module's matcher once per loader; the placeholder pool is made on first use. Woven
bytes and manifests identical over the five corpora. Ceiling startup allocation 7,968 MB to 7,373 MB
(agent stacks 6,566 to 5,990), young GCs 90 to 81, startup within noise (one run each).

Chunk 7b landed: `JdkTypePool`, one per agent, answers only `java.*` names (and primitives),
through the platform loader with an LRU of 1024 types, as the parent of each transform's
placeholder pool and of `describeClassFile`'s pool; a description made there resolves what it names
through the platform loader too, which a first cut missed (249 of 302 JDK types failed a walk on
primitives and `javax.` interfaces) and a test now pins. PetClinic's startup caches 302 types, at
most about 6.7 MB. `CodeSourceClassFile` reads a woven class's own class file from its code source
(ADR 0052 amended), verified live against Spring Boot 4.1.1's `jar:nested:` locations (590 reads,
no failure, on PetClinic). `DeflectedClassLoadTest` relied on the agent's own-file read hitting the
loader from inside a transform; it now triggers the nested load from a transformer ahead of the
agent, with the same expectations. Ceiling, medians of three: total allocation 7.4 GB to 6.1 GB,
agent stacks 6.0 to 4.7 GB, type parsing 1.42 GB to 0.54 GB, unprofiled young GCs 77 to 62,
"Started" 8.0 s to 7.4 s.

Closing measurements, local (2026-10-05, `8139271`, the same method and machine as `c4f91d7`'s,
medians of five): headline startup 5.48 s to 3.83 s over no agent's 3.18 s (+0.66 s, +20.7%, from
+69%), ceiling 13.84 s to 8.55 s; pinned in Docker, the headline was +25.4% on one CPU and +24.0% on
two (from +83%). Ceiling live heap after delivery +117.5 MB to +26.5 MB, `ProbeRegistry` 64.6 MB to
3.0 MB, table and byte caches empty once quiet, G1 committed +178 to +90 MiB. Two findings, grilled
with Luke the same day: the `java.*` pool (6.7 MB) was never released, so it joins the quiet release
(Q18's rule); and 7b's code-source read opened Boot's `jar:nested:` handler inside the transform,
loading its classes where no transformer sees them (17 Boot loader classes unreported at the
ceiling, against one before), so jar shapes are warmed up first (Q24, below). Startup target (Q25):
the headline config's time to first 200 within 25% of no agent on the runner, a target and not a
gate, a miss being the trigger for the `DECORATE` grill.

Chunk 7c landed: a directory is read from its code source at once (the JVM never lets the `file`
handler be replaced); each jar shape (a jar file, a `jar:file:` location, a `jar:nested:` location)
is read once on a warm-up thread outside any transform, created without inheriting thread-locals,
while its transforms read through the loader; a miss of any kind returns the shape to unwarmed,
and 16 leave it on the loader; a jar file's later URLs inherit the handler the warm-up used, so a
handler factory registered afterwards (standalone Tomcat's) never runs in a transform. The first
cut detected handler overrides through `java.protocol.handler.pkgs` only; review found that a
factory or a location carrying its own handler bypass the property, so every jar shape warms. The
ceiling rerun's unreported set is back to `MetaInfVersionsInfo` alone. `releaseQuietCaches` clears
the `java.*` pool when no loader was active since the previous call. ADR 0052 amended for the
warm-up and its window.

Chunk 7d landed: the main analysis pass records each method's tracked-instruction sequence for
`SitePairing` while it visits, and one `ClassReader` over the analysed bytes serves the analysis,
the condition fingerprinter, the size guard and (when the received bytes are the class file) the
header restore, so the constant pool is decoded once. Woven bytes and manifests identical over the
five corpora; ceiling startup allocation 6.13 to 6.02 GB. The stored plans' apparent growth (1.12 to
1.30 KB a class) was attribution: since chunk 6a the registry no longer shares the interned names,
so MAT counts them against the plans; reachable plan bytes are identical at `c4f91d7` and after.

The runner (workflow run 37330002272, agent at `8139271`, one pinned core, six repeats, every run
valid, no class skipped): headline startup 16.9 s to 20.7 s, **+22.4%** (from +76%), inside Q25's 25%
target; throughput, CPU per request and p95 within noise; RSS +66 MiB. Ceiling: startup 18.2 s to
58.2 s (from 15.2 s to 64.0 s), throughput -3.5%, CPU per request +3.6%, RSS +266 MiB. The README
carries the table. The ceiling's one-core startup improved far less on the runner than pinned
locally in Docker (21.5 s over 6.8 s on one CPU), which nobody has looked into; the `DECORATE`
round attacks the per-class transform cost that dominates it.

Chunk 7e landed: the condition fingerprinter rides the main analysis pass through a tee wrapped
around every method's visitor chain (`ConditionFingerprinter.FingerprintCollection`), so the class
is walked once; a fingerprinter exception is caught at the tee and costs that class its
fingerprints, never its analysis, as the separate pass's catch did. A method with no tracked site
returns its empty result before building label maps, and window fingerprints share one builder.
Fingerprints, keys, conditions, woven bytes and manifests identical over the five corpora and every
test fixture class; ceiling startup allocation down about 87 MB. The fingerprinter's remaining 160 MB
is its own per-instruction records, left since shrinking them would restructure the algorithm.

The 2026-10-04 write-ups were removed when this work landed (git history keeps them, at
`2e0dbe4` and `5a47ad0`); their scripts are the manual profiling kit in
`benchmark-overhead/profiling/`.

### A `$default` method's 32nd optional parameter is never counted: fixed

Fixed 2026-10-05: the analyser's test for a mask constant takes any single set bit, `Int.MIN_VALUE`
included, so all 32 parameters of the first mask `int` get an omission probe;
`MethodProbeWeavingTest` covers 31, 32 and 33 optional parameters. A `$default` with 32 or more
optional parameters gains a slot and a new layout hash. What follows is the entry as found.


Found 2026-10-05 by `DECORATE` chunk 2's tests. The analyser picks the optional parameters of a
`$default` method from its mask tests with a power-of-two check that needs a positive value, so bit
31 (`Int.MIN_VALUE`) is never read as an optional parameter and gets no omission probe; ADR 0021
says the first 32 are counted. A function needs 32 or more optional parameters to meet it. Fixing it
adds a slot and moves that method's layout hash, which is free before release.

### `DECORATE` instead of `REBASE`, and the entry probe in ASM: landed

Grilled on 2026-10-05 with Luke, from an Opus research pass with a standalone harness over 3,184
corpus classes (byte-buddy 1.18.12; scratch in the session's `decorate/` directory). Findings:
`DECORATE` (`ByteBuddy.decorate`, `DecoratingDynamicTypeBuilder`) skips `MethodRegistry.prepare`,
`InstrumentedType` representation and the supertype parses entirely, writes the class header
verbatim (3,184 of 3,184 identical to the received bytes, where the rebase changed 384 without the
restorer) and refuses `defineField`, `defineMethod` and `initializer`. Per class, ByteBuddy's weave
took about a third of the time and 40% of the allocation, and an ASM entry probe halved what was
left; every woven class in every mode defined and initialised exactly as its unwoven twin. Under a
strict pool the rebase failed 42 to 68 classes per corpus on absent types (what ADR 0059 patched)
and `DECORATE` none. The agent at `8139271` skips `kotlin.Result`
(`Cannot resolve T from private static final ? kotlin.Result.getOrNull-impl(?)`, from
`MethodRegistry.prepare`), so every generic `@JvmInline value class` is skipped today; `DECORATE`
weaves it.

Settled:

- **Adopt both before 0.1.0 (Q26):** `DECORATE` for the method tier, and the entry and omission
  probes written in the agent's own ASM instead of `Advice`.
- **A custom type strategy (Q28):** a `DecoratingDynamicTypeBuilder` subclass with
  `TypeAttributeAppender.NoOp`, since stock `DECORATE` with annotation retention resolves each
  class-level annotation type (about 1.7 parses per Kotlin class, identical bytes);
  `Implementation.Context.Disabled` is set on the `ByteBuddy` instance by hand, since
  `disableClassFormatChanges()` keeps only the stock strategy. `ForDeclaredMethods` stays.
- **The missing-supertype guard stays (Q29):** a class whose supertype is absent is refused and
  reported as skipped, the walk reading supertype names through the agent's cached class-file reads
  instead of the placeholder pool.
- **Branchless omission probes (Q30):** per optional bit, add `(mask >>> i) & 1` to its slot; no
  frames, no locals; a supplied parameter writes +0 (ADR 0003's accepted race).
- **No accessor frame below version 50 (Q31),** accepted in the normalised comparison.
- **`SizeGuard.ADVICE_TAIL` goes to 0 (Q32).**
- **`AgentBuilder` stays (Q33);** raw ASM would save about 13 µs a class and cost its listener,
  circularity handling, pool and description.

Landing order (Q27), one chunk and one commit each: (1) `DECORATE` with ASM writing the below-55
field, `<clinit>` prelude and accessor and the refusal marker, keeping `Advice`, proved by byte
equality with today apart from the rebase's `<clinit>` scaffolding and member order below 55; (2) ASM
entry and omission probes, proved by equality apart from `Advice`'s goto, nop, entry frame and first
line label; (3) the placeholder pool, `PlaceholderCounts` and the header restorer deleted, the
supertype guard re-homed. ADRs amended with each chunk: 0058 (only the class-visitor half of
validation can run), 0059 (superseded except the supertype rule), 0060 (the mechanism becomes ASM),
0061 (the `Advice` and rebase frame exceptions go); 0052 and 0053 unchanged in substance.

Chunk 1 landed: `DecoratingTypeStrategy`, a `DecoratingDynamicTypeBuilder` subclass with no type
attribute appender and the tier's configuration (validation off, `ForDeclaredMethods`, no method
ignored, `FrameRefusingClassWriter`, a disabled implementation context), replaces the rebase; the
`ByteBuddy` an `AgentBuilder` carries is no longer read. `ProbeArrayMembers` writes the below-55
field, prelude, accessor, slow path and the refusal marker in ASM. Against `6daeda4`'s output over
1,350 corpus and version-matrix classes: 632 byte-identical, 716 equal once the rebase's `<clinit>`
scaffold, member order below 55 and the below-50 accessor frame are normalised, manifests identical;
two Kotlin `$$inlined$groupingBy$1` classes gain an entry probe on `keyOf`, whose generic signature
disagrees with its descriptor, so the rebase's description never matched it and `Advice` never wove
it (a false never-hit, pinned by a test). A generic `@JvmInline value class` weaves (pinned). Weave
time per class fell 20 to 40% and allocation 25 to 40% over the corpora. Review added a test of a
pre-55 initializer that loops to its first instruction and catches, a check that the interface
accessor's slow-path call carries `itf`, and the strategy's fallback version. ADRs 0058 to 0061
amended.

Chunk 2 landed: `MethodProbes` writes the entry probe (`probes[slot]++`) and the branchless
omission probes as ASM at a method's first instruction, in the positions the two `Advice` uses held;
the `dev.otherlode.advice` package, its offset mappings and `ProbeArrayForm.asStackManipulation`
are gone, and the probe member names live on `ProbeArrayForm`. `SizeGuard.ENTRY_PROBE` drops the
`Advice` tail. Against `b1dec7d` over 1,350 classes and 12,326 methods: 9,382 entry-probed methods
identical once `Advice`'s goto, frame, nop and moved line label are normalised; 189 `$default`
methods differ only in the omission prefix; manifests identical, so no layout hash moved. Weave
allocation per class down 13 to 19% more. ADRs 0002, 0021 and 0061 amended. Found while testing,
not changed: mask bit 31 is never read as an optional parameter, so a `$default` counts its first 31
optional parameters, not 32; fixing it moves slots and layout hashes, a follow-up below.

Chunk 3 landed: `PlaceholderTypePool.kt` (the pool, `PlaceholderCounts`, the placeholder type) and
`ClassHeaderRestorer` are removed, with the placeholder log line and the test toggle;
`SupertypeGuard` keeps ADR 0059's one surviving rule on cached class-file headers. Woven bytes,
canonical manifests and the 46 supertype refusals identical over the corpora and the version
matrix; weaving 5 to 10% faster again. The chunk also showed that under the decoration nothing
describes a `java.*` type, so 7b's shared JDK pool (Q21) never filled: it is removed too, with its
quiet release, and the method tier's pool is ByteBuddy's plain one. `CONTEXT.md` drops
"Placeholder" and says the supertype rule under "Skipped class"; ADR 0059 amended as superseded but
for that rule.

What follows is the entry as raised.


Raised 2026-10-05 by the startup allocation pass. The method tier rebases every woven class, and
ByteBuddy's rebase (`MethodRegistry.prepare`, `InstrumentedType.Factory.represent`,
`RebaseDynamicTypeBuilder.toTypeWriter`) with the type parses it triggers allocated about 1.7 GB of
PetClinic ceiling startup's 6.5 GB, plus a share of its time. OpenTelemetry's agent uses
`DECORATE`, which rewrites method bodies without describing the type for a rebase. Since ADR 0060 a
class from version 55 gets nothing added, so `DECORATE` fits there; below 55 the probe field, the
`<clinit>` prelude and the accessor would have to be written in ASM. It may also make ADR 0059's
placeholder pool and the class header restorer redundant, since both exist to keep ByteBuddy's
description of the type out of the written class. Writing the method-entry probe in the branch
tier's ASM visitor instead of `Advice` would remove `Advice`'s per-instruction cost (125 MB of
`getStackSize` under `BranchProbeMethodVisitor`) and the per-weave matcher work. To settle: whether
to adopt either before 0.1.0 (Luke's rule says yes unless something blocks), what happens to ADRs
0058 to 0061, how the version-below-55 form is written and verified, and how
`WovenClassVerificationTest` and the corpus tests prove the switch byte-for-byte where it should be
identical.

### A Scala 3 enum nested in a class fails to transform: closed by ADR 0058

Closed 2026-10-05: "Illegal modifiers" was ByteBuddy's type validation, which
ADR 0058 turned off. `EnumHolder$Inner$` weaves, the scala corpus weaves 307
of 307 classes with none failed (`CodeSizeLimitsTest`'s report), and
`WovenClassVerificationTest` defines and initialises it beside its unwoven
twin. What follows is the finding as recorded.

Found 2026-10-04 by the overhead grill's size count. Running the real
transformer over the `scala` benchmark corpus, `EnumHolder$Inner$` fails with
ByteBuddy's "Illegal modifiers 16385" (`ACC_PUBLIC | ACC_ENUM`). The class is
skipped and reported, so it is not a false finding, but its probes are lost.
`ScalacMatrixTest` runs the analyser only and never reaches ByteBuddy, so
nothing covers it. Not yet investigated: which release emits the flags, and
whether a top-level Scala 3 enum's module class transforms.

### An unread body is an unread shape: landed

Grilled on 2026-10-03 with Luke; ADRs 0054 and 0055, with amendments to 0025,
0026, 0038, 0048 and 0052. Terms: unread shape, read release. Every exact-body
rule fell back to "the adopter's code" on a body it could not read, and the
agent's real matcher over scalac 2.12.18 to 3.10.0-RC3 showed that is already a
false finding in the field: from 2.13.17, 3.3.7 and 3.7.1 every case class's
`hashCode` reads as dead code, and 3.9.0, the new LTS, keeps 609 of 819 marks.
kotlinc 1.9.25 to 2.4.20 and javac 17 to 25 were stable apart from three gaps
in ADR 0026's rules.

The decisions, in short:

- A body inside an outline of compiler output that matches no read shape is an
  unread shape: probed, counted, never a finding or a cluster node, listed apart
  by family. Version-keyed for Scala 3 (the `.tasty` header names the release),
  version-blind elsewhere, since Scala 2, kotlinc (`mv` is the language
  version) and javac (the class-file version follows `--release`) do not name
  the compiler.
- Outlines: Scala case-class, companion, object and enum plumbing and static
  forwarders; a multi-file facade's methods; coroutine machinery; the collision
  side of an unread string-switch lowering. None for data classes and enums
  (name rules cannot drift) or for `@JvmOverloads` and `$DefaultImpls`
  forwarders (Java can write them).
- Every Scala variant the sweep found is read; 2.12's `readResolve` is
  `SCALA_OBJECT`; Scala 3 enum plumbing is read. A declared compiler list, one
  fixture build per variant boundary, an exact Scala 3 release table filled by a
  sweep script, and a scheduled canary on the newest release of each compiler.
  ADR 0055's amendment made the table a list of read releases, swept by the
  matrix test itself.
- ADR 0026's gaps: kotlinc 2.1 and earlier's `disable`-mode stub forwarding to
  `$DefaultImpls` is marked and passes through; the `$DefaultImpls` forwarder
  rule accepts kotlinc's null checks; a record's methods are marked only with
  the `ObjectMethods` indy body.
- Wire, with no consumers to keep compatible: `oneof origin { generated_by;
  unread_shape }` on `ProbeLocation` and `DeclaredMethod`, `oneof { routine;
  unread_shape }` on `BranchOutcome`, `ResourceAttributes.agent_version`. No
  compiler field.
- The no-class-file case of ADR 0052 stays a recorded gap with an INFO count.

Landing order. One Sonnet chunk per step, reviewed in the main session, big
fixes re-reviewed by a fresh Opus reviewer, one commit each:

1. Kotlin and javac fixture matrix. Landed 2026-10-04. `fixtures-compilers`
   compiles one Kotlin source set with kotlinc 1.9.25, 2.1.21, 2.2.21 and
   2.4.20 and one Java source set with javac 17, 21 and 25; the versions are
   listed once, in `gradle.properties`. KGP 2.2.21's Build Tools API
   `compilerVersion` could not drive 1.9.25 or 2.4.20, so each release's own
   `K2JVMCompiler` runs from `kotlin-compiler-embeddable` against its own
   stdlib. `KotlincMatrixTest` and `JavacMatrixTest` run the real analyser over
   every build; javac 17's throwing default is asserted on real javac 17 output.
   The review added the fixture class directories as inputs of the test task
   (a fixture change used to leave tests up to date, for the existing Scala and
   Kotlin fixture modules too) and made the compile tasks configuration-cache
   safe. No rule gap beyond the three in step 2 turned up.
2. ADR 0026's three gaps. Landed 2026-10-04. The `disable`-mode stub, the
   `$DefaultImpls` forwarder's null checks and the record body rule, each
   proven across the matrix. The reviews widened the stub rule to generic
   interfaces (erased parameters, a cast on the return, boxed primitives),
   found that a `super.m()` override compiles to the stub's exact body (marked,
   by ADR 0048's precedent), and found that passing a call through an open
   class's stub, or kotlinc 2.2's bridge in its place, dropped the virtual
   edge a subclass override is reached by, and that a call naming a subclass
   that only inherits the stub reached nothing once the stub was marked. ADR
   0041 is amended: the virtual edge is kept, an inherited forwarder or
   bridge is resolved at the class that declares it, and a call to an abstract
   interface method also reaches the interface's own `$DefaultImpls` body. A
   sub-interface's inherited defaults (`J$DefaultImpls.p` forwarding to
   `I$DefaultImpls.p` with a receiver cast) are marked too. The fourth review
   showed a call typed as a super-interface cannot be fixed in the agent, so
   ADR 0024 is amended: a collector looks through a generated method along its
   own edges instead of stopping at it. The testkit and the stub collector
   apply it; `otherlode-server` takes it in step 9. The fifth review added that
   a generated method with hits is a hit caller (a `HashMap` calling a data
   class's `hashCode` reaches an override), and two forwarder shapes: a boxed
   primitive result under kotlinc 2.2's default mode, and `Boxing.box<Type>` in
   a suspend forwarder. The sixth found the boxed-result acceptance also let a
   hand-written `super` call through a `$DefaultImpls` method be marked, and
   narrowed it to calls to the interface's own accessor. Seven Opus reviews.
3. Scala fixture matrix and every variant read. Landed 2026-10-04.
   `fixtures-compilers/scalac` compiles the baseline modules' sources with
   2.12.20, 2.13.16, 2.13.18, 3.3.6, 3.3.7, 3.3.8, 3.7.0, 3.7.2, 3.8.3, 3.8.4
   and 3.9.0, each with its own compiler and library (the `-version` output
   and, for Scala 3, the `.tasty` header prove which ran), and
   `ScalacMatrixTest` requires every method's mark to equal the 2.13.15 or
   3.3.4 baseline's. The review found three over-matches and a fourth worth
   closing: 2.12's shapes (no prefix mix, source-order `equals`, the
   `Integer.toString` out-of-range, `readResolve`) were read in any class, so a
   2.13 or Scala 3 override written by hand in that shape was hidden. They are
   read only where 2.12 can have written the class: a Scala 2 class with no
   `productElementName`, and for `readResolve` a Scala 2 module class with no
   `writeReplace`. For step 6: the matcher's variants are a union with no
   record of which release wrote which, so keying Scala 3 on the `.tasty`
   release needs them tagged. For step 10: 3.3.3 and 3.4.0 name lambdas and
   enum `$anon` constructors differently from 3.3.4, so the comparison, which
   also checks method sets, fails on them; the canary has to compare marks of
   the methods both builds have.
4. Scala 3 enum plumbing. Landed 2026-10-04. The enum class's `Product`
   forwarders, the companion's `values`, `valueOf`, `fromOrdinal`, `$new` and
   `ordinal`, a singleton case's plumbing (its `hashCode` from 3.3.7 on the 3.3
   line and from 3.7.3) and a parameterised case's `ordinal` are `ENUM`, each by
   exact body, recognised by structure only scalac writes (an abstract
   `ACC_ENUM` class implementing `scala.reflect.Enum`), and checked on every
   release from 3.3.3 to 3.9.0; 3.3.3 joined the matrix, which now flags only
   marked methods present in one build. Two reviews: the first found a given,
   a nested object or a nested case class in the companion counted as a case,
   two `fromOrdinal` and one `valueOf` lowering unread, and 3.3.3's and
   3.4.0-3.4.1's wording; the second found none of the fixes over-matched, and
   that counting cases read each parameterised case's whole class (now only its
   header). Each case still costs one class read per enum class transformed, a
   number that grows with the square of the case count: about 6,900 reads for
   an 80-case enum, once, at load. The overhead entry above should measure it.
   Not read, and recorded: a Java-compatible enum (`extends java.lang.Enum`), an
   enum declared inside a class or locally, backticked case names, an enum that
   overrides `productPrefix`, two case names with the same hash, and 3.10's
   companion, which drops the parameterised cases' fields (the canary will show
   it).
5. Wire. Landed 2026-10-04 in this repo. `UnreadShape`, `oneof origin` on
   `ProbeLocation` (`generated_by` 16, `unread_shape` 26), `DeclaredMethod` (5,
   13) and `BranchOutcome` (`routine` 7, `unread_shape` 8), and
   `ResourceAttributes.agent_version` (8), filled from the agent jar's
   `Implementation-Version`, which the shadow jar's manifest now carries. The
   model refuses a probe that is both generated and unread. Moving the two
   fields into oneofs is the intended break `buf breaking` reports, so the
   schema was pushed to the registry by hand, as the buf workflow says an
   intended break must be. The testkit stores both fields and gained a public
   `agentVersion(serviceInstanceId)`, for item 4's review; nothing judges them
   yet. For steps 8 and 9: an unread-shape method is no node, and like a
   generated method (ADR 0024's amendment) the graph looks through it and a hit
   one is a caller, or an unread forwarder would cut a path the way a marked
   stub did in step 2.
6. Unread shapes for Scala. Landed 2026-10-04. Outlines per class kind, the
   `.tasty` reader (top-level file by name, cached per loader and per scan),
   `scala3-read-releases.txt` (3.3.3 to 3.9.0, every one swept), version
   keying, propagation to branch and omission probes and the static baseline,
   a per-class WARNING for an unread release, and a first-flush summary that
   separates unread releases from classes whose compiler cannot be told. On real
   output no Scala 3 method is unread; a Scala 2 build's unread methods are the
   fixtures' hand-written lookalikes and `Q`, scalac's own plumbing for a case
   class whose element overrides a superclass `val`. The review narrowed the
   Scala 2 object outlines, kept an enum's companion and case classes unread on
   a read release (ADR 0054, amended), and made the scan's jar test able to
   fail. A second review narrowed that hold to the methods scalac refuses to let
   the adopter write, gave an enum declared inside a class (no `MODULE$`) its
   outline, kept 2.12's `readResolve` beside a hand-written `writeReplace`, and
   split the summary into three causes. Not done: unread shapes are recorded at
   transform time, before ADR 0007's commit point, so a transform that then
   fails is still counted in the summary; classes first loaded after the
   summary are never logged; a local enum gets no outline; an enum inside a
   class reports scalac's `ordinal(E)` as the adopter's on a read release; and
   Scala 3.3 at its default Java 8 target writes `valueOf` and `fromOrdinal`
   with a `StringBuilder`, which reads as unread shapes until it is read (ADR
   0055, amended).
7. Unread shapes for the other outlines. Landed 2026-10-04. A kept site in a
   suspend-shaped method fed by kotlinc's `label`, its prologue marker local or
   the `dup` marker form, matching no read shape, gives every outcome
   `COROUTINE_MACHINERY`; the collision side of each bucket's last check in an
   unread string lowering over a compiler temporary is `SWITCH_LOWERING` (kotlinc
   and javac); a facade method that is no forwarder is `MULTIFILE_FACADE`; and
   classes analysed from their received bytes are counted in one INFO line on
   the first flush. The review found two outlines marking the adopter's own
   code, a local holding the public marker and a hand-written `hashCode()`
   switch, now excluded (ADR 0054, amended), and one reader gap in real output:
   a string `when` whose `null` shares a branch with a literal
   (`"a", null -> ...`) is not read by ADR 0038's lowering, so its collision
   sides read as unread shapes, not dead code; kotlinx-coroutines' `DebugKt`
   has one. Reading it is after release. As in step 6, outcome counts and the
   received-bytes count are taken before ADR 0007's commit point.
8. Testkit and stub collector. Landed 2026-10-04. `neverHit()` leaves out an
   unread-shape method, a branch probe in one, and an unread outcome;
   `neverHitUnreadShapes()` lists them with `ProbeRef.unreadShape`, and
   `neverHitRoutineOutcomes()` no longer includes them. Omission findings
   abstain on an unread target, class judgements and never-loaded treat an
   unread shape as a generated method, and the call graph looks through it and
   counts a hit one as a caller (ADR 0024, amended). The stub collector applies
   the same rules and prints an `UNREAD SHAPES` section.
9. `otherlode-server`. Landed 2026-10-04 in `b200bc2` and `6dbc56a` (server
   ADR 0053). Both oneofs and `agent_version` are stored, an unread shape is
   never judged, rows carry `unread_shape`, `/probes` takes `include_unread`,
   and the report has an `unread_shapes` block. The graph looks through
   generated and unread methods and counts a hit one as a caller, as the
   testkit does, with two recorded differences: across instances the server
   lets any mark win where the testkit lets any unmarked report make a node,
   and an edge from a loaded class the agent did not probe blocks a claim on
   the server where the testkit drops it. Both matter only with several
   instances, or with unreported classes, in one test JVM. The server's own
   gaps are in its STATUS, under "Unread shapes and the agent version".
10. The canary and the README. Landed 2026-10-04. `compiler-canary.yml` runs
    weekly: `.github/scripts/newest-compilers.sh` extends the kotlinc and
    scalac lists with the newest stable release of each line from Maven
    Central, and the matrix tests run over them; a red build is the signal. A
    new Scala 3 release fails the read-list test by design, until it is swept
    and listed. The kotlinc `-jvm-default` default became a rule ("enable from
    2.2"), so a new kotlinc fails only on a shape change. The README lists the
    compilers whose marks are exact. Run locally on 2026-10-04 with scalac
    2.12.21 added (the only newer stable release): everything passed. Not yet
    run on GitHub; the first scheduled run is Monday 2026-10-05, or start it by
    hand from the Actions tab.

Open from step 2's review, after release: kotlinc 2.2 and later under an
explicit `-jvm-default=disable` make each implementing class's stub a bridge,
which has no probe, so a call typed as a super-interface whose sub-interface
overrides the default does not reach that sub-interface's `$DefaultImpls`
body. The default mode from 2.2 is unaffected. Also: a value class as a generic
interface's type argument (`Impl : I<V>`, kotlinc 2.1 and earlier) gives a
stub that boxes through `V.box-impl`, then casts and unboxes the result; it is
not read and reads as the adopter's code (ADR 0026's amendment). `Unit` as
the type argument gives a stub for a default returning the type parameter
whose body pops the result and returns void (`get()V`), not read either. Both
shapes turn up in a sub-interface's `$DefaultImpls` forwarders too
(`SV$DefaultImpls.put-<hash>`, `SU$DefaultImpls.put`). A data class with a
value-class property is not recognised at all: its `componentN` and `copy`
names are mangled (`component2-<hash>`), so none of its generated methods is
marked (kotlinc 1.9.25 and 2.1.21; ADR 0026 already leaves value classes out).
A suspend default returning `Unit` gives a `$DefaultImpls` forwarder that ends
by comparing the result with the suspended marker and returning `Unit`
(`dup; getCOROUTINE_SUSPENDED; if_acmpne; ...; getstatic Unit.INSTANCE`): a
sub-interface's under `disable` on every kotlinc, and the interface's own under
`all-compatibility` before 2.2. Not read; a false never-hit when unused.

Open from the survey, after release: javac 25 at `--release 21` lowers an enum
pattern switch through `typeSwitch` with ConstantDynamic `EnumDesc` arguments,
and every javac lowers a qualified enum label the same way; `bootstrapSwitch`
reads neither, so those cases keep numeric keys (readability only, ADR 0038's
amendment). `$default`'s super-marker null check is not checked by the mask
rule; nothing has shown a body where that matters.

### A class file read through the wrong loader path: closed by ADR 0052's amendment

Closed 2026-10-05 by chunks 7b and 7c of the startup round, which took this entry's second option
for a different reason (Boot's nested-jar loader made every own-class read probe up to 107 jars): a
woven class's own class file is read from its `ProtectionDomain` code source, the copy the JVM
defined, for a directory, a jar file and `jar:file:`/`jar:nested:` locations, so a loader that
defines child-first but serves resources parent-first no longer hands the agent its parent's copy.
Two narrow cases still read through the loader, and so keep the gap described below: a class with
no code source or one of another shape, and a class transformed while its jar shape is warming up
(the first few classes of each shape, ADR 0052's amendment). Detecting a mismatch in those two
cases (the third option) stays unbuilt until a loader of the bad shape turns up. What follows is
the entry as recorded.


Raised 2026-10-03 at the review of chunk 1 of the JaCoCo work (ADR 0052, which
records it as a consequence); not yet grilled. The analysis reads a class's
class file through its loader's `getResourceAsStream`, which is parent-first
unless the loader overrides it. A loader that defines classes child-first but
serves resources parent-first, with another version of the class on its
parent's path, hands the agent the parent's class file. Marks, keys and lines
then describe that version, and a method only the child's version declares
gets no probe, which is a silent absence. The test fixture loader had exactly
this shape and was made consistent. Tomcat's, Jetty's and the common
child-first loaders override both lookups alike; nobody has shown an adopter's
loader of the bad shape.

Options to weigh: leave it as a recorded gap until a loader of this shape
turns up; read the class file from the class's `ProtectionDomain` code source
instead, with a per-location cache, which has to handle Spring Boot's nested
jar URLs (`jar:nested:` from Boot 3.2, `jar:file:...!/BOOT-INF/...` before)
and classes with no code source; or detect a mismatch (the class file's
method set or `SourceFile` disagreeing with the received bytes beyond what an
earlier transformer adds) and fall back to the received bytes with a WARNING.
The question is how much machinery a so-far hypothetical loader earns, set
against ADR 0007's "silent, confident, wrong".

### Another agent ahead of or behind this one: landed

Found in the deep review of 2026-10-03 and grilled the same day; ADRs 0052
and 0053, pre-release checklist item 3. Terms: class file, received bytes,
earlier transformer.

The order goes wrong both ways. With JaCoCo ahead, every result read from a
body's shape is read from JaCoCo's output: `GeneratedBy` marks, routine
kinds, switch lowering and throwing defaults fall back to plain, so generated
plumbing reads as never-hit adopter code, and branch keys, conditions, guards
and the layout hash differ from an instance without JaCoCo. A `finally` copy
reads as `NONE`, since a probe sits between the handler's `aload` and
`athrow`; a `NULL_DEFAULT` or `THROW_ONLY` path holds a probe's `bastore`;
scalac's string match is not recognised, since a probe sits between
`hashCode` and the `lookupswitch`. With this agent ahead, JaCoCo breaks
instead: it identifies a class by a CRC64 of the bytes it receives
(`Instrumenter.java:75`, 0.8.13) and its report hashes the class file
(`Analyzer.java:106`), so every woven class reads "Execution data for class X
does not match" with zero coverage. That is Gradle's default for an adopter:
`Test.jvmArgs("-javaagent:...")` is copied ahead of the `jacoco` plugin's
argument provider (`DefaultJavaForkOptions.copyTo`, 8.14). Our own suite only
shows the first order, because its tests self-attach after JaCoCo's
`premain`. The JAX-RS module counts too: it weaves the adopter's resource
classes.

Facts the plan rests on, read from source: the JVM calls every transformer
that is not retransformation-capable before every one that is
(`jvmtiExport.cpp:975`, jdk21u), and JaCoCo, AspectJ's weaver and Spring's
weaver carrying Hibernate's enhancer are not; JaCoCo keeps every original
conditional jump and switch of a method, in order, adds none inside one, and
inverts a jump whose target needs a probe; on a retransformation, a capable
transformer receives the bytes from before it ran, and a field missing from
its output fails the whole batch.

1. **Read the class file.** The analysis reads the class file through the
   class's loader; the rewrite walks the received bytes. Per method, the Nth
   site in the class file pairs with the Nth tracked jump or switch received,
   with its polarity: the same opcode, the inverse (outcomes swapped), or a
   failed pairing. Identical arrays skip the pairing. A method that does not
   pair keeps its METHOD probe and marks and gets no branch probes, its sites
   left out rather than reported at zero, one INFO line per class. No class
   file: the received bytes, logged at FINE. Layout hash, keys, marks,
   routine kinds, conditions and guards all come from the class file, and
   which methods and `<clinit>` get probes too, so a `<clinit>` JaCoCo adds
   to a Java 8 to 10 interface gets the prelude and no probe. ADR 0052 and
   its pointers in 0025, 0026, 0037, 0038, 0046 and 0048. Proof: the 63
   JaCoCo-only failures green under the init script; JaCoCo's offline
   `Instrumenter` for inverted jumps; a fake earlier transformer adding a
   branch for the fallback.
2. **Retransformation-capable.** Both tiers register as capable, with nothing
   retransformed at install. On a retransformation or redefinition, a class
   this agent wove is woven again from the plan its first weave stored (ADR
   0053's amendment), and every other class gets null; type
   descriptions come from the passed bytes, since ByteBuddy's default reads
   the loaded class, which already has the field; no second registry commit
   and no second JAX-RS declaration. ADR 0053 and the ADR 0005 amendment.
   Proof: a second agent retransforms a woven class, the call succeeds, and
   counts keep going.
3. **Both orders, the standing check, the docs.** A forked-JVM test with
   `jacocoagent.jar` in both command-line orders, each asserting our manifest
   equals a run without JaCoCo and JaCoCo's ids match the class files'
   CRC64; the retransform leg; a CI job on every push running the whole
   suite with JaCoCo applied by an init script under `gradle/`, which must
   pass; the testkit README; this entry closed; CLAUDE.md's design sections.
   The job lands last, since a red job on `master` would block every push
   between.

Chunk 1 landed: `SitePairing` pairs each method's tracked instructions between
the class file and the received bytes, with polarity, and the rewriter places
each method's slots from the class file's numbering and checks them per method,
so a method asking for more than its run skips the class instead of shifting
another method's counts. The suite under JaCoCo went from 63 failures to 0, and
`EarlierTransformerInstrumentationTest` runs JaCoCo's `Instrumenter` as an
earlier transformer inside the normal suite. Facts the design did not predict:
`StaticBaselineScannerTest`'s four JaCoCo failures were the same cause, the
manifest side read from JaCoCo's output; a method the received bytes lack
gets no probe at all (ADR 0052 amended), since a probe with nothing to weave
into reads as never hit; and switch pairing also compares which entries go to
the default, since slot counts depend on it.

Chunk 2 landed: both tiers and the capture are registered
retransformation-capable through `makeRaw()` and `addTransformer(t, true)`,
with nothing retransformed at install, and describe classes with `POOL_ONLY`.
The first weave stores a `WeavePlan` per class (`WovenClasses`), and a later
call for a woven class is woven again from it, never renumbered, refused only
when the class file is readable, was read at first weave, and differs. Two
review rounds changed the design (ADR 0053's amendment): the first version
gated on a hash of the received bytes, which made an earlier capable agent's
retransformation fail, since HotSpot caches the input to the first capable
environment that changed the class; and a re-weave that recomputed the analysis
from a changed class file could keep the layout hash while counting into the
wrong outcome. The plan therefore holds each paired method's tracked
instructions and its dropped, throwing-default and unprobed ordinals, and a
re-weave runs no analysis. Measured over the fixtures, a plan costs about 100 to
180 bytes per probed method depending on how shared strings are counted, and
reaches no loader, class or type description. First weaves were checked
byte-identical to chunk 1's over 453 woven fixture classes, and re-weaves
byte-identical to first weaves. Facts the design did not predict: the
`endpoints-*` test agents needed `Can-Retransform-Classes` in their manifests;
an endpoint module that throws on a re-weave loses its advice for that class
without landing in `disabled_endpoint_modules` (logged once per module; its
endpoints then read as never called from that point, recorded here rather than
fixed, since its inputs equal the first load's); and `BranchKeys` already
calls `MessageDigest` inside every first weave, so JCA initialisation inside a
transformer predates this chunk.

Known gap from chunk 1's review, the class file read through the wrong loader
path: its own entry above, to grill.

The JVM keeps an off-heap copy of each woven class's received bytes once the
transformer is capable, about one class-file length each; no flag, and the
overhead entry measures it. The default when a body is not recognised, which
this fix's fallback can still reach, is its own entry.

Chunk 3 landed: `CoverageAgentOrderTest` launches the fixture program in three
JVMs (this agent alone, JaCoCo's real agent jar listed first, and listed after)
and checks that the manifest and every count match the run alone and that
JaCoCo's id for each fixture class is its class file's CRC64. With chunk 2's
registration reverted only the listed-after JaCoCo check fails, and with
chunk 1's class-file read or its polarity handling reverted the manifest or
count checks fail in both orders. The `jacoco-check` CI job runs `check` with
`gradle/jacoco-check.init.gradle.kts` on every push: 1896 tests, all passing
under JaCoCo on 2026-10-03. The README says the order is free for JaCoCo and
that an agent which changes a method's jumps ahead of this one costs that
method its branch probes. A failed fork is remembered, so a hanging one costs a
single timeout.

### Deep review of 2026-10-03: landed, with follow-ups

A review of the whole repo by a reviewer agent, each finding verified with a
failing test before its fix. Landed: Spring MVC joins an inherited mapping to
its declaring class, counts the best of several matching patterns, counts the
default handler under `/*` instead of one endpoint per URL, declares
`registerMapping` endpoints (Actuator), declares a nest's method-only routes,
and skips CORS preflights; a JDK `HttpServer` handler the JDK refused never
becomes the join; Ktor's dispatch advice ignores its own coroutine
resumptions; the endpoint pipeline leaves runtime-generated proxies alone;
route templates keep every parameter in a segment and a regex holding `/`;
the OTel bridge treats `*` and `HEAD` endpoints as owned; the endpoint
registry holds framework route objects weakly. In the branch tier: kotlinc's
and scalac's string `when` no longer probe an outcome only a hash collision
reaches; a `finally` body with no normal-path copy, a throw the method
catches itself, and a counting null side are no longer routine; a class
kotlinc regenerated from a library's inlined object is not read as own code;
a `label` field of the adopter's own is not taken for the state machine;
branch keys include an `invokedynamic`'s constants and method references;
dropped inlined copies keep their origin, so key presence no longer depends
on scope. Elsewhere: a static baseline send that fails is retried after a
confirmed flush; a jar with no classes reads as resources only in the testkit
and the stub; the testkit depends on a new `:wire` module only, and the
shaded agent relocates its own wire packages, so the agent runs from its
`-javaagent` jar alone; redefinition of a woven class is ignored (ADR 0005);
a sub-resource class declares no JAX-RS endpoints (ADR 0020); a flush
interval above one day falls back to the default; the testkit rejects a
second instance in extension mode, records undecodable payloads, takes a
read lock for queries, and injects into `PER_CLASS` constructors.

Recorded, not built:

- **A coverage agent ahead of this one silently drops marks.** Grilled and
  planned in its own entry above, "Another agent ahead of or behind this
  one".
- **A nested `try`/`finally` written on one line loses one `FINALLY_COPY`.**
  kotlinc emits no line number for the outer `finally`'s exception copy in
  `try { try { … } finally { … } } finally { if (b > 7) … }` on one line, so
  that copy inherits the inner body's line, finds no twin there and reads
  `NONE`. Normally formatted, the same nesting is marked. Contrived; noted so
  nobody rediscovers it.
- **The other two repos read `RESOURCES_ONLY`.** The server needs the rule
  the testkit and the stub apply: a dependency every listing counted no class
  in is never unloaded. No wire change: `class_count` already carries it.
- **Ktor regex routes merge with their parent.** `PathSegmentRegexRouteSelector`
  contributes nothing to the template. It is absent from Ktor 2.0.3, and its
  `getRegex()` returns a `kotlin.text.Regex` the shaded jar would rename, so a
  fix reads `toString()` (`Regex(<pattern>)`) and has to decide how a pattern
  holding `/` or `:` becomes one identity segment.
- **The OTel bridge still duplicates a route under a servlet context path.**
  OpenTelemetry's route includes it and a framework module's identity does
  not.
- **A class kotlinc regenerated from a library's inlined object keeps its
  method probes.** Its branch sites are now dropped as library copies, but
  `Foo$bar$$inlined$sortedBy$1.compare` is still a METHOD probe at a library
  line, and a never-hit row when the sort saw one element.
- **Branch keys of conditions holding an `invokedynamic` changed** with the
  fingerprint fix, a one-time break in their cross-build history. Nothing is
  live, so `DERIVATION_TAG` was not bumped.
- **Small edges the follow-up review left.** Spring's annotation-mapped side
  still counts a CORS preflight as a hit of the `GET` it asks about, since its
  dispatch advice sees no request; the functional side skips preflights. A
  route normalised before this review as `/{id:\d+}.json` normalises now as
  `/{id}.json`, so a fleet mixing agent versions sees one endpoint under two
  identities until it upgrades. An unclosed brace in a template swallows the
  slashes after it, and a regex holding `}` (`{p:[^}]+}`) closes early. Weld's
  proxies are not recognised as runtime-generated, so a JAX-RS resource under
  CDI still counts twice. The OTel bridge records OpenTelemetry's `HTTP` verb
  for an unknown method as its own endpoint.

### Library paths show the home folder as `~`: landed

Landed on 2026-10-03 (ADR 0051, `13368e7`), from the hosted service's
privacy review. `DependencyRegistry.register` stores a dependency's
display location with a leading `user.home` written as `~`, so neither
the registry nor the wire holds the running user's name. Only a whole
leading folder matches, and on Windows the match ignores case. The real
path stays in `DependencyOrigin`, which never leaves the agent. A path
under another user's home folder still shows that user's name; the
hosted service's privacy policy says so.

### The rename from Yukon to Otherlode: landed in this repo

The rename landed here on 2026-09-30 (ADR 0049): the package and Maven
group `dev.otherlode`, the schema as package `otherlode.v1` in
`otherlode/v1/otherlode.proto` on `buf.build/otherlode/otherlode`,
`otherlode.*` and `OTHERLODE_*` settings, `/v1/otherlode/...` ingest paths,
and the testkit's `OtherlodeTestCollector` and `OtherlodeExtension`. The
collector, the server and GCP still carry the old name and follow. Until the
collector serves `/v1/otherlode/...`, this agent cannot send to it.

### Routine outcomes: landed in all three repos

Settled 2026-09-26 in a grilling session with `otherlode-server`: ADR 0046 here,
server ADR 0039, server STATUS item 17. Some outcomes are real but not worth
a person's time when they never run, and the agent reads them exactly from
the bytecode, where a backend would have to guess from condition text.

Chunk 1, the agent: `RoutineKind` and `BranchOutcome.routine` (field 7) on
the wire. `RoutineClassifier` runs inside the guard analysis on each kept
outcome's path, the code its outcome node dominates, and gives it
`FINALLY_COPY` (a site in a catch-any handler that stores what it caught and
throws it again), else `THROW_ONLY` (the path only builds an exception and
throws it), else `NULL_DEFAULT` (the null side of a null check whose path
calls nothing before it rejoins or returns). The testkit and the stub
collector leave routine outcomes out of never-hit, the headline and the
cluster graph; the testkit lists them through `neverHitRoutineOutcomes`, and
the stub prints them under `ROUTINE OUTCOMES` with their kind. In the demo,
the four null paths of `totalParam` and the null side of `/promo`'s `?: ""`
are `NULL_DEFAULT`, and the two checkout conditions are not routine.

Every shape was confirmed with `javap` on Kotlin 2.2.21 and javac 21 output
(`RoutineTarget.kt`, `RoutineJavaTarget.java`) before it was coded.

Open from this chunk:

- `NULL_DEFAULT` is the null side only. The non-null side of `x ?: load()`
  also calls nothing, but it yields the value itself, not a default, so it
  stays a finding.
- A throw path whose message reads a Kotlin property of another class calls
  its getter, so it is not `THROW_ONLY`. Neither is one that formats its
  message with `String.format`. Only the calls `RoutineClassifier`'s doc
  lists count as building a message.
- javac's try-with-resources catches `Throwable`, a typed catch, so nothing
  in its handler is a finally copy. The resource's null check is
  `NULL_DEFAULT` on its null side on both paths; the rest of its shape is not
  recognised. Kotlin's `use {}` holds no site of its own.
- A `Throwable` is resolved through the analyser's class lookup. A class it
  cannot read counts as one when its name ends in `Exception` or `Error`.
- The testkit and the stub collector fold sites as server ADR 0031 does
  (2026-09-26): a site folds when its method was never hit, a lone
  constructor that is not a row included, or when its guard outcome is never
  hit and not routine. Review of that chunk found the lone-constructor case
  missing and it was fixed the same day. The stub
  lists four routine outcomes, and `PromoHandler.handle`'s `query ?: ""`
  site folds into the method's row.

The rest landed the same day: collector bindings (`f827a13`), the server
(`b1294fe`) and the web UI (`1831561`). Checked end to end: the headline
reads 2 conditions where it read 6; see the server's STATUS, item 17.

### A service's namespace, and OpenTelemetry's own settings: landed in all three repos

Settled 2026-09-26 in a grilling session across all three repos: ADR 0045,
with server ADR 0038 and collector ADR 0002. The server keys a service by
its namespace and its name, so the agent sends a namespace, and it reads
the values an adopter has already given OpenTelemetry.

Chunk 1, the agent: a `serviceNamespace` option with no default, and
`ResourceAttributes.service_namespace` (field 6), sent only when set. The
name, the namespace and the environment fall back from Otherlode's own three
sources to OpenTelemetry's own settings, resolved as its Java agent
resolves them: `otel.service.name` and `otel.resource.attributes` each come
whole from the system property, else from `OTEL_SERVICE_NAME` or
`OTEL_RESOURCE_ATTRIBUTES`, and a list with a pair that is not `key=value`
is ignored whole, as the specification says. The name then falls back to `ServiceNameDetector`, which mirrors
OpenTelemetry's Spring Boot, manifest and jar detectors, and last to
`unknown_service:java` in place of `unknown-service`. The stub collector
and `runDemoStack` print a namespace when there is one, and `runDemoStack`
passes `OTHERLODE_SERVICE_NAMESPACE` to the demo server.

The rest landed the same day: the collector's namespace processor and
shard key (collector `38b1a0e`), the server's keying, routes and web UI
(server `fa30548`, `c72ed4d`), and `runDemoStack` reading the demo under
its namespace (`a18a278`). A name or namespace of `.` or `..` is skipped
with a warning at each source (`bd3052b`), since browsers drop dot
segments from the URL the server shows it at; the collector rejects such
a payload too (`c36c608`). Checked end to end with two `runDemoStack`
runs, one with `OTHERLODE_SERVICE_NAMESPACE=shop`: see the server's STATUS,
item 16.

Open:

- The YAML reader in the detector covers block mappings only. A Spring
  name written as a flow mapping is not detected, where OpenTelemetry's
  would be.
- A blank OpenTelemetry system property falls through to its environment
  variable, where OpenTelemetry would let it hide the variable.
- Agent-level redaction of literals is parked in the server's STATUS,
  item 18. The README says an agent posting straight to a backend sends
  literals in clear.

### Readable branch findings: landed in all three repos

Settled 2026-09-24 in a grilling session across all three repos, after
reading the `otherlode-server` UI against the demo. A never-hit outcome reached a
person as "Branch 12, `DemoServerMain.kt:121`": no condition, no side, and
nothing about what code it leads to. ADRs 0037 (sites, conditions, guarded
code, guards) and 0038 (string and enum switches read back to source
cases), collector ADR 0001 (redaction processor), server ADR 0030 (read by
site, newest run describes a row). Terms: condition, site key, guarded code,
guard.

Landing order, one chunk per commit:

1. Agent: a transform-time benchmark over the demos and a Spring Boot app,
   with numbers recorded here before any analysis lands.
2. Agent: sites on the wire. `BranchSite` on METHOD probes and on
   `DeclaredMethod`, with site index, site key, line and its outcomes
   (branch index, role); site index on BRANCH probes. Switch roles carry
   the numeric case key until chunk 5.
3. Agent: guarded code and guards. Control-flow graph and dominators per
   method; guarded and partly guarded line ranges, SMAP-mapped, on each
   site's outcomes; a guard on each site and call edge, in the manifest
   and the baseline. Benchmark read again.
4. Agent: conditions as code and literal parts, for Kotlin, Java, Scala 2
   and Scala 3, each idiom confirmed with `javap` first.
5. Agent: string and enum switches read back to source cases, with the
   lowering's jumps and throw-only defaults dropped as machinery.
6. Collector: bindings bump and the redaction processor.
7. Server: bindings bump, migration 0002, and storing every new fact.
8. Server: reads by site, display fields from the newest in-scope run
   (method lines included), and the report counting sites.
9. Server: the web UI's site rows in never-hit and stale-hit and in the
   graph's expanded method nodes; the branch index and key no longer
   shown.
10. End to end: the compose stack plus `runDemoStack`, and each repo's
    STATUS brought up to date.

Chunk 1 landed: `./gradlew jmh` times `BranchSiteAnalyzer.analyze` over
five corpora (README, "Benchmark the transform-time analysis"). Baseline on
an Apple M3 Pro, JDK 21.0.4, 1 fork, 3 warmup and 5 measurement iterations
of 5 s:

| Corpus | Classes | ms per corpus | Per class |
|---|---|---|---|
| demo | 61 | 5.811 ± 0.088 | 95 µs |
| demo-spring | 9 | 0.124 ± 0.002 | 14 µs |
| scala | 56 | 1.045 ± 0.194 | 19 µs |
| spring-webmvc | 545 | 41.239 ± 0.668 | 76 µs |
| ktor-server-core | 477 | 35.488 ± 0.309 | 74 µs |

The review replaced a corpus of the agent's own classes: `TypeMatchPolicy`
never includes the agent's package, so the analyser dropped their inlined
copies and call edges, a shape no adopter class has. Ktor's server core
stands in as the large Kotlin corpus. `demo` costs more per class than the
others, which is worth a look when chunk 3 reruns this.

Chunk 2 landed: `BranchSite`, `BranchOutcome` and `BranchRole` are on the
wire, on METHOD probes and on `DeclaredMethod`, and a BRANCH probe names its
site. `KeptBranchSite.of` is the one numbering the manifest and the baseline
share, and a site key uses the branch key's collision rules under its own
`site-v1` tag. Two things the design did not predict: a one-case switch also
has two outcomes, so `BranchSite.isSwitch` tells it from a conditional; and
the review found the manifest and baseline chunk weights ignored the new
sites, so `BranchSite.chunkWeight` (one per site and one per outcome) now
counts in both, and chunk 3 extends it to line ranges. ADR 0037 was amended
before this chunk to list outcomes inside their site, since the baseline has
no BRANCH probes to carry them.

Chunk 3 landed: `GuardAnalysis` builds each method's control-flow graph
with one node per kept outcome, computes dominators, and gives each outcome
its guarded and partly guarded `LineRange`s and each site and call edge its
guard, in the manifest and the baseline alike. `InstructionRecorder` sits in
front of the analyser's visitor, so call candidates and graph nodes share one
instruction ordinal, pinned by `InstructionRecorderTest`. Facts the design did
not predict: a suspend function's label switch keeps only its first case in
the graph (recorded in ADR 0037), since the resume cases rejoin mid-method and
would bypass every outcome; and `ExportScheduler`'s rider packing also had to
count site weight. The review made ranges merge across lines with no code, so
a blank line or comment no longer splits an arm. A same-file inline function
called from both arms leaves its line partly guarded by each, which is true.

Benchmark after chunk 3, same settings:

| Corpus | Baseline ms | Chunk 3 ms | Change |
|---|---|---|---|
| demo | 5.811 | 7.362 ± 0.107 | +27% |
| demo-spring | 0.124 | 0.132 ± 0.001 | +6% |
| scala | 1.045 | 1.026 ± 0.013 | none |
| spring-webmvc | 41.239 | 48.645 ± 0.346 | +18% |
| ktor-server-core | 35.488 | 39.825 ± 0.170 | +12% |

Chunk 4 landed: each kept site carries its condition as `CODE`,
`STRING_LITERAL` and `PLACEHOLDER` parts, written by `ConditionWriter` from
the fingerprinter's own window, in Kotlin, Java or Scala. The demo's stub
collector prints `System.getenv("ENABLE_LEGACY_DISCOUNT") == "true"` was never
true, only path to `DemoServerMain.kt:59`. Every idiom was confirmed with
`javap` first. Facts the design did not predict, now in ADR 0037: a Kotlin
template is written as a `+` concatenation, since a template's constant pieces
could not stay separate literal parts for redaction; and `if_acmp` reads as
`===` in Kotlin, since kotlinc compiles `===` and enum `==` alike, except
between enums (checked through `ACC_ENUM` by the class lookup), where `==` is
exact. The review made widening conversions transparent and narrowing ones
read as `x.toInt()`, `(int) x` or `x.toInt`.

Open, from chunk 4: a Kotlin extension call reads as its static facade call
(`StringsKt.toDoubleOrNull(value)`, not `value.toDoubleOrNull()`), and a
mapped built-in as a Java call (`this.length()`). Telling an extension apart
needs the callee's Kotlin metadata, which the agent does not parse. A Scala
`object` read through `MODULE$` is a placeholder until its shape is confirmed.

| Corpus | Chunk 3 ms | Chunk 4 ms | Change |
|---|---|---|---|
| demo | 7.362 | 8.267 ± 0.204 | +12% |
| demo-spring | 0.132 | 0.137 ± 0.002 | +4% |
| scala | 1.026 | 1.188 ± 0.026 | +16%, two more fixture classes |
| spring-webmvc | 48.645 | 51.362 ± 0.662 | +6% |
| ktor-server-core | 39.825 | 44.429 ± 0.453 | +12% |

Chunk 5 landed: `SwitchLowering` recognises javac's and kotlinc's enum
mapping switches, javac's string index switch and javac's `SwitchBootstraps`
pattern switches, and rebuilds each as one site named by its constants,
literals or types, with the lowering's own jumps dropped as `SWITCH_LOWERING`.
kotlinc's and scalac's string matches have no index switch, so only their hash
switch and null check are dropped, and each `equals` check stays a site that
reads `status != "Aa"`. A throwing default (`MatchException`,
`IncompatibleClassChangeError`, `NoWhenBranchMatchedException`) keeps its
branch index but gets no slot, no probe and no graph node. A case's key comes
from its label, so it survives a case being added and javac renumbering its
`$SwitchMap$` holder from `$1` to `$2`, pinned by compiling two fixture
versions. ADR 0038 was brought in line with what the `javap` evidence showed:
the two rules by shape, `null` as a label, cases in instruction order, and the
rule-two keys moving when kotlinc swaps `ifeq` and `ifne`.

| Corpus | Chunk 4 ms | Chunk 5 ms | Change |
|---|---|---|---|
| demo | 8.267 | 8.288 ± 0.117 | none |
| demo-spring | 0.137 | 0.137 ± 0.001 | none |
| scala | 1.188 | 1.578 ± 0.025 | 72 classes, up from 58: +7% per class |
| spring-webmvc | 51.362 | 50.880 ± 0.649 | noise |
| ktor-server-core | 44.429 | 42.794 ± 0.207 | noise |

The agent side of the landing order is done. Across chunks 2 to 5 the
analysis costs 11 to 43 percent more per class than the baseline.

The `demo` corpus is the demo module's whole Kotlin output, so
`runShapesStack`'s shapes (2026-09-26) add eight classes to it; numbers
measured after that are not comparable with the tables above.

Chunk 6 landed in `otherlode-collector` (`a09f9cd`): a `Redaction`
processor replaces `STRING_LITERAL` parts a blocked pattern matches, or all
of them, and clears unknown fields while it is on. The collector's bindings
moved to `v1.36.12-20260924225937-af331e73c211.2`, generated from `b2618cd`.
The review kept one deviation and recorded it in collector ADR 0001: an
unreadable `OTHERLODE_COLLECTOR_REDACT_ALL_LITERALS` stops startup instead of
reading as false.

Chunk 7 landed in `otherlode-server` (`8f28a79`): migration 0002 stores sites,
outcomes, conditions, case labels, guarded lines and guards per run and per
scan, and the guard joins each call edge table's key through `UNIQUE NULLS NOT
DISTINCT`. The review found Postgres cannot store NUL in jsonb or text, so a
part whose text holds NUL is stored as a placeholder instead of failing its
payload on every retry.

Chunk 8 landed in `otherlode-server` (`3c1990f`): never-hit and stale-hit
return `rows`, each a method row or a site row carrying every outcome with
`in_finding`, and paging counts rows. A merged row's display fields come from
the newest in-scope run. The report has `methods` and `branch_sites` in place
of the mixed probe triple. An outcome with no site forms a site of its own
rather than dropping out.

Chunk 9 landed in `otherlode-server` (`b6c649b`): the never-hit and stale-hit
tables, the graph's expanded method nodes and the headline read sites through
one module, and the branch index and key are shown nowhere. The browser check
found the mono font drawing `!=` as one glyph, which blurs `==` against
`===`, so mono text has ligatures off.

Checked end to end on 2026-09-25 against the rebuilt compose stack, with
`runDemoStack` (`53ff83c` taught its report printer the server's site rows):
the server reports 2 methods and 7 conditions with an untaken path, and
reads `System.getenv("ENABLE_LEGACY_DISCOUNT") == "true"` was never true,
only path to `DemoServerMain.kt:59`. The browser shows the same rows, and the
graph's `totalParam` node lists its four conditions with a true and false
marker each.

The follow-ups this left in `otherlode-server`'s STATUS have since landed there:
folding a dead method's branches into its row (item 2), rooting clusters at
a never-taken outcome (item 3), and routine outcomes (item 17, with ADR 0046
here). Redaction stays out of the server; agent-level redaction is parked
there as item 18.

### Readable names and findings, UI items 3 and 6 to 10: landed in both repos

Settled 2026-09-25 and 2026-09-26 in grilling sessions driven by
`otherlode-server`'s STATUS list "Names and findings a person can act on",
each checked end to end with `runDemoStack` and in the browser. The
server side of each is in that repo's ADRs 0032 and 0034 to 0037; the
agent side, one ADR and chunk each:

- ADR 0039 (`2077d68`): a never-taken outcome roots the unreached
  cluster behind it; the testkit and stub collector apply the rule.
- ADR 0040 (`4f8cea6`): each method's `static` flag on the wire, and
  `@JvmOverloads` forwarders marked `JVM_OVERLOADS`. Parity for server
  ADR 0034's class findings in `15c49f9`. Demo shapes in `70441f3`
  (`AuditLog`, `ReceiptPrinter`, `Money`, `Price`).
- ADR 0041 (`e34eb72`): each class's Kotlin kind from
  `@kotlin.Metadata`'s `k`; multi-file facade forwarders marked
  `MULTIFILE_FACADE`; generated forwarders pass calls through; multi-file
  parts, which kotlinc marks synthetic, are probed. Demo gains the
  two-file `DemoText` shape.
- ADR 0042 (`c774947`): a `CREATES` edge carries the interface its
  `invokedynamic` implements.
- ADR 0043 (`2df937b`): each method's parameter names, generic
  signature and extension-receiver flag.
- ADR 0044 (`5ef0082`): an omission probe carries its default value's
  line, not its target's: the line in effect at the fill block in
  `f$default`, or a Scala getter's own line. A Scala constructor
  getter's static forwarder has no line-number table and sends -1.

`ConditionInstrumentationTest` finds the demo checkout's condition
lines in the demo source (`52392b1`), so demo edits no longer break it.

Open from these:

- A `@file:JvmName` single-file facade is never probed: ByteBuddy
  refuses to redefine a class carrying `@kotlin.jvm.JvmName`, so the
  agent reports it skipped. Its code is never judged.
- `LoadedClassSweep` still drops every synthetic class, so a multi-file
  part that reached no transformer is not reported.
- A javac inner-class constructor with no generic types has no
  `Signature` to drop its outer instance by, so `otherlode-server` shows
  `this$0`. A flag read from the class's `InnerClasses` attribute would
  close it.
- A receiver lambda whose captured values come first gets no
  `extension_receiver` flag, since only the first parameter is read.
- `runDemoStack`'s printer still prints JVM names on its endpoint and
  optional-parameter lines, and an any-verb route as `* /checkout`
  where `otherlode-server` shows `ANY /checkout`.

### Nothing is published anywhere

No build in this repo publishes an artifact. An adopter cannot depend on the
agent jar or on `otherlode-testkit` except by building them, which also means the
testkit's whole reason for existing, letting someone else's test suite assert
on dead code, has no distribution. The wire schema is the one thing that is
published, to the Buf Schema Registry, and CI does that on every push that
touches the proto.

What gets published, under which version, and the testkit's frozen surface
were settled on 2026-10-04 (ADRs 0056 and 0057, the versioning superseded by
0062), and how a release is cut and published on 2026-10-05 (ADR 0063). The
publishing itself is checklist item 6.

### Naming hidden code: landed in all three repos

ADR 0034, with server ADR 0028. A1 (`bcd30a8`) sends `CallEdge.kind`
(`CREATES` for invokedynamic targets and body-class methods),
`captured_count`, `lambda_body` and each class's `source_file`, with
`ClassSupertypes` renamed `ClassLocation`. A2 (`07e95d7`) sends
`body_kind` and `source_name`, and makes every body class the agent
does not probe a pass-through: kotlinc's reference classes, `$sam$`
wrappers and suspend-function continuations. The collector follows the
rename in `a64ab18`, and the server stores, names and shows the facts.

Checked end to end on 2026-09-23 with `runDemoStack`: `main$lambda$0`
(the `/__shutdown` handler) reads as a lambda body created in `main` at
`DemoServerMain.kt:48`, `main` creates `handleCheckout`, and
`handleCheckout` creates `respond` through `val send = ::respond`.

### Naming a hidden handler: landed in both repos

ADR 0035. An endpoint whose handler reaches the framework through an
`invokedynamic`, such as `/checkout` (`::handleCheckout`) and
`/__shutdown` (a lambda) in the demo, gets its handler name from the
JDK's lambda factory. Advice on `spinInnerClass()`, installed by
retransformation after a reflective shape check, records the method
each `HttpHandler` lambda class calls in a weak map in the bootstrap
seam, and the three `HttpServer` advices read it back. A miss stays
null. The members were checked on JDK 21, 22, 25, 26 and 27, and CI
runs the suite on 21 and 25.

A1 landed on 2026-09-23 (`365cbe7`): the hook, the seam,
`EndpointModule.handlerInterfaces`, the `HttpServer` switch and the CI
matrix. `runDemo` names `/checkout` as `DemoServerMainKt#handleCheckout`
and `/__shutdown` as `DemoServerMainKt#main$lambda$0`.

A2 landed on 2026-09-23 (`da06d7a`): the forwarder table. The analyser records a
pass-through a handler can be reported as, only for a handler interface
and only when the call-edge walk reaches one concrete target: scalac's
`$adapted` forwarder named by an `invokedynamic`, and the `handle` of a
kotlinc reference class under class-based SAM conversion, written when
its creator is analysed. `RegistryResolver` applies the table on
`register` and `attachHandler`. Spring's functional module names
`HandlerFunction` and asks the seam for a hidden handler in its declare
walk and its dispatch advice. A `$sam$` wrapper keeps its own name,
since it only calls the function value it holds. No wire or collector
change in either chunk.

`otherlode-server` followed on 2026-09-24 (`37f690b`): `/endpoints` rows
carry the handler's `created_in`, lambda-body flag and captured count,
so its endpoints table reads `/__shutdown`'s handler as "lambda in
`main`". Checked end to end on 2026-09-24 with `runDemoStack` against
the rebuilt compose stack: `/checkout` joins `handleCheckout`,
`/__shutdown` joins `main$lambda$0`, and neither handler is an entry
root on the graph.

### Run id on every payload: landed in all three repos

ADR 0032. The agent makes a random run id once per process and stamps it on
`ResourceAttributes.run_id` (field 5), and every delta batch, manifest chunk
and baseline chunk from one process carries the same value. `ProbeManifest`
carries `ResourceAttributes resource = 14` in place of its own
`service_name`, `service_version` and `service_instance_id`, which are
reserved by number and name, so the manifest also gains `environment`.
`ResourceAttributes.forNewRun` is the one place the id is made; `Agent.start`
calls it once and hands the result to the scheduler and the baseline scan.
The change is breaking on purpose, since nothing is released. `buf lint`
passes. `buf breaking` flags the three removed manifest fields, and CI runs it
only on pull requests, so a push to `master` publishes to BSR.

`ExportScheduler` takes the resource as a required parameter, so a caller
cannot get a second run id by leaving it out; tests build theirs with
`TestResources.forConfig`. The demo's stub collector keys every class,
endpoint and dependency map on (instance, run) and answers 400 to an empty
run id. The testkit collector keys on instance alone, which holds for the one
agent in its own JVM (ADR 0018). It answers 400 to an empty run id and to a
second run id under a known instance id, and lists both in
`rejectedPayloads()`. After any rejection, `awaitSettled` and every other
public query and wait throw `IllegalStateException` listing the reasons, so a
test fails even if it never checks `rejectedPayloads()`.

The other two repos followed on 2026-09-22. `otherlode-collector` (`b40113a`)
bumped its bindings, reads the manifest's identity from `resource`, and
rejects any payload with an empty run id; its shard key stays service plus
instance, so a restart does not move an instance to another shard.
`otherlode-server` (`704a8ff`, its ADR 0024) keeps each run as its own row
under its instance, with every per-run table hanging off the run, and
merges with `max()` within a run. That replaced its reset-aware merge and
its wipe on a version change. `runDemoStack` passes against the rebuilt
stack.

### Dependency usage: landed, with follow-ups

Grilled and settled on 2026-09-21; ADR 0030 holds the decision and
`CONTEXT.md` the terms (dependency, reference, absent reference, live
reference, and the unloaded, unreferenced and unreached statuses). Chunk 0
(wire and codec) has landed. Two wire choices the grill left open were
settled in it: a dependency's cross-instance identity is the sorted set of
its `group:artifact` pairs (one pair for an ordinary jar), and
`DependencyLocation.identity_source` says whether that came from
`pom.properties`, the jar manifest or the filename. The proto documents
`DeltaBatch.dependency_deltas` as one entry per dependency whose
`loaded_classes_total` changed since the last delivered batch, the probe-delta
rule; chunk 2 must send them that way.

Chunk 1 (listing, identity, registry, manifest delivery) has landed, with
three changes to the design the grill wrote down, all in ADR 0030. The listing
runs on a background thread, not in `premain`: streaming a fat jar's nested
jars to judge the adopter's-own rule took about 200 ms for `demo-spring`'s 20
MB. Identity falls back from `pom.properties` straight to the filename:
`Implementation-Title` is a display name, and three Tomcat jars share
`Apache Tomcat`. A fat jar's dependencies are every file under `BOOT-INF/lib/`
(a war's `WEB-INF/lib/` and `lib-provided/`), since a packaged launch never
reads `classpath.idx`. Against `demo-spring` the listing finds all 36 nested
jars, 14 identified by `pom.properties` and 22 by filename, every Spring jar
among the latter with an empty group. Chunk 2 matches loaded classes back
through `DependencyOrigin` and must count nothing until
`DependencyRegistry.isListingComplete`.

Chunk 2 (counting, discovery by load, `DependencyDelta`) has landed. On
`demo-spring` a nested class's code source is
`jar:nested:<outer>/!BOOT-INF/lib/<jar>!/`, parsed as Boot 4.1.1's
`NestedLocation` does (split at the last `/!`, `%` escapes decoded, the Windows
drive fix); 31 of the 36 dependencies load a class, none is discovered by load,
and the sweep reads no jar. The Boot 2 `jar:file:<outer>!/<entry>!/` form was
checked on 2026-09-24 against the real `spring-boot-loader` 2.7.18 and 3.1.12,
each in a fat jar built by hand around one app class and three libraries. Both
loaders gave exactly that form for `BOOT-INF/classes` and for a nested jar. The
used library counted its one loaded class, the other two read as unloaded, and
all three came from the startup listing. One thing stays open: a flat jar first
seen at load still goes through the agent-jar rule, which rightly turns away a
dynamically attached agent's jar but would also turn away `byte-buddy-agent`
sitting flat in an exploded war's `WEB-INF/lib`; it would then read as no
dependency rather than as one.

Chunk 3 (references in the analyser, `ExternalClass` on the manifest) has
landed, with one change to the design: only runtime-visible annotations are
references. Counting every annotation made `org.jetbrains:annotations` read as
referenced on every Kotlin service, through kotlinc's class-retention
`@NotNull`, though the jar can leave the runtime classpath with nothing
changing. On `demo-spring` 14 referenced classes map to five jars
(kotlin-stdlib, spring-boot, spring-boot-autoconfigure, spring-context,
spring-web), none absent, no JDK type listed. Every Kotlin class references
`kotlin.Metadata`, so kotlin-stdlib always reads as referenced and live, which
is true. A referenced class whose loader throws from `getResource` is recorded
as absent. The proto no longer promises every referenced name an
`ExternalClass` entry: one may arrive a manifest later, and a name that never
gets one belongs to no dependency.

Chunk 4 (references in the static baseline) has landed. The scan has no
defining loader and in a fat jar cannot see `BOOT-INF/lib`, so it maps names
through a class index the listing builds only when the baseline is enabled
(11,356 names for `demo-spring`), released as soon as the baseline has read
it. The publisher waits for the listing, which releases the wait on failure as
well as success, with a two-minute backstop. Two resolution choices: a name
seen at the root of a jar counts as the adopter's own only when no dependency
holds it, since on a flat `-cp` classpath the scan walks dependency jars too;
and the baseline never records a name as absent, because it cannot tell a
missing class from one in a jar only a runtime loader opens, and the first
recording of a name wins. `StaticBaseline.external_classes` stays empty; every
mapping travels on the manifest.

Chunk 5 (`references_recorded`, the stub's dependency report, the Spring demo)
has landed. `ProbeManifest.references_recorded` is set on every manifest when
the include rules are set, so a collector can tell an instance that records
nothing from one that references nothing (since ADR 0033 a running agent
always has include rules, so it is always true; the field stays on the wire
for the collectors that read it); the collector judges each dependency
only by the recording instances that list it. `runSpringDemo` reports 5
unloaded (commons-lang3 among them), 26 unreferenced (spring-webmvc and the
rest of the framework), jackson-databind unreached from the never-loaded
`LegacyPricing#apply`, and 5 used. Two agent-side fixes came out of the demo
runs: a filename version must contain a dot (`endpoints-ktor-2.jar` had read
as `endpoints-ktor` at version 2 and merged with ktor-3; `jsr305-3.jar` now
reads as one artifact with no version), and a jar holding the agent's own
package is never a dependency, since an unshaded agent build carries no
`Premain-Class`. The demo server and client run on a classpath of their own
classes and kotlin-stdlib, so the agent runs from the shaded jar and the plain
demo lists no agent library as its dependency.

Chunk 6 (testkit) has landed: `dependency(group, artifact)`,
`awaitDependency`, `unloadedDependencies()`, `unreferencedDependencies()`,
`unreachedDependencies()` and `absentReferences()`, applying a port of the
demo's rules, with the demo's cases ported too and three more added to both.
The split queries throw rather than return an empty list when the include
rules or a complete baseline are missing, so an assertion of "none" cannot
pass on missing data. The two-batch gate `dependency()` had at first is
replaced by ADR 0036's delivery order; see "Dependency delivery order". The
`agentTest` suite runs with `staticBaselineEnabled=true` at no measurable cost
and proves unloaded, used and the split end to end against two fixture jars.
Chunk 7 has landed in `otherlode-collector` (`4bc51e7`): its generated bindings
are bumped to BSR commit `df061083`, which carries every dependency field, it
forwards them untouched, and `LogSink` logs their counts and
`references_recorded`; the relay round-trip tests carry each field.

Chunk 8 has landed in `otherlode-server` (`f417d3b`): migration 0012, a port of
the rules with all 25 of the demo's cases as store subtests, `GET
/api/v1/services/{s}/dependencies` with a status filter, `GET
/absent-references`, and a `dependencies` block on `report` with a
`split_available` flag. Proved end to end with the compose `stack` profile
and `:demo:runDemoStack`: the server's answer for the plain demo matches the
stub's (annotations unloaded, protobuf-java and byte-buddy unreferenced,
kotlin-stdlib used). The server judges a baseline complete from each
instance's latest complete scan, as its never-loaded rule does, and counts a
sweep-reported class as loaded; the stub and the testkit differ from it only
in the "never loaded" mark on a site, never in a status. Protobuf-java and
byte-buddy were the agent's own libraries, on the demo server's classpath by
mistake; since `94d64af` the plain demo lists only annotations (unloaded) and
kotlin-stdlib (used). The stack run was repeated on 2026-09-24, after
`otherlode-server` took up the flag, and the server's answer matched.

Open, recorded rather than started:
- A `byte-buddy-agent` jar sitting flat in an exploded war's `WEB-INF/lib`
  is turned away by the agent-jar rule and reads as no dependency.
- Every Kotlin service reads kotlin-stdlib as used through `kotlin.Metadata`,
  which is true but says nothing about the adopter's own use of it.

Landing order as built, one chunk and one commit each:

0. Wire and codec: `DependencyLocation`, `DependencyDelta`,
   `referenced_classes`, `ClassReferences`, `ExternalClass`.
1. The startup listing and identity reading: flat jars, Boot's nested jars,
   nested `pom.properties`, the agent-jar rule, the adopter's-own-jar rule.
2. Sweep counting and `DependencyDelta`.
3. Analyser references, resolution to a dependency, absent references, in the
   manifest.
4. The same in the static baseline.
5. Stub collector and demo. `demo-spring` gains one runtime dependency nothing
   touches (unloaded); `jackson-databind`, which Spring loads and the demo never
   names, is the unreferenced case, which needs the baseline flag in `runDemo`;
   one reference in the never-hit promo branch is the unreached case.
6. Testkit.
7. `otherlode-collector` bindings bump.
8. `otherlode-server`: `GET /api/v1/services/{s}/dependencies` with a status
   filter, and a `dependencies` block on `report`.

### Dependency delivery order: landed in both repos

The testkit's `dependency()` gate counted two delta batches after the listing's
manifest, which assumes one batch per flush; a flush over 20,000 deltas sends
several beside its manifest, so a used jar could read as unloaded.
`absentReferences()` had no gate and returned an empty list until the listing
arrived. ADR 0036 settles it (2026-09-24): the agent sends a dependency's entry
only after a confirmed delta send carries its first counts, holds a reference
mapping until its dependency's entry is delivered, and stamps
`ProbeManifest.dependencies_listed` once the startup listing and the mappings
recorded before it ended are delivered. `CONTEXT.md` has the term.

Landing order, one chunk and one commit each, built with `/chunked-build`:

0. Wire and codec: `ProbeManifest.dependencies_listed`.
1. Agent: counting generations in `DependencyRegistry`, delivery recorded when
   every delta send of a flush is confirmed, entries and mappings held until
   then, one more manifest send in the same flush for what that releases, the
   flag, and an empty manifest to carry it.
2. Testkit and stub collector: a dependency is judged once its entry arrives;
   list queries and `absentReferences()` throw until every instance sent the
   flag; `awaitDependenciesListed`; the stub logs the flag.
3. `otherlode-collector`: bindings bump, `LogSink` logs the flag.

Chunk 0 has landed (`c243ff3`): field 15 on `ProbeManifest`, the agent's model
and codec. Chunk 1 has landed (`761a475`): counting generations in `DependencyRegistry`,
entries and mappings held until a flush's delta sends are all confirmed, a
second manifest send in that flush for what it releases, and the flag with an
empty manifest to carry it. The independent review found three things the
brief did not predict. Every sweep after the listing counts, so the delivered
generation rises on most flushes, and the second send is keyed on an entry
waiting to go out, not on the generation alone. The empty flag manifest runs
only after a flush whose sends were all confirmed, so an outage never adds a
send. And the sweep's confirmation pass could throw before the count, which
would have held every dependency back for good; the count runs in a `finally`.
On the plain demo the releasing flush sends three manifests: probes, then the
dependencies and mappings, then the flag.

Chunk 2 has landed (`0f6fcd2`): the testkit judges a dependency once its entry arrives,
the list queries and `absentReferences()` throw until every instance heard
from has sent the flag (and while none has been heard from), and
`awaitDependenciesListed` waits for it. The stub collector logs the flag and
names, after the counts line, any run that never sent it. The review found
that the testkit stored a manifest's entries before its mappings, on a handler
thread outside the query lock, so a query woken between the two could read a
used jar as unreferenced. The store writes mappings first, entries next and
the flag last. `dependency()` judges on the instances whose entry has arrived;
with several instances, `awaitDependenciesListed` first covers the rest.

Chunk 3 has landed in `otherlode-collector` (`74d7a9b`): bindings bumped to BSR commit
`084ba94b`, `LogSink` logs the flag, and the relay round-trip test carries it.

`otherlode-server` takes the flag up under its ADR 0029: dependency reads carry
`listing_complete` and the instance list `dependencies_listed`.

Recorded, not planned:
The testkit's `awaitSettled` counts delta batches the same way and keeps the
split-flush weakness for hit totals.

### The agent refuses to start without include rules: landed

Grilled and settled on 2026-09-23; ADR 0033 holds the decision, and ADR 0030's
consequences were amended. With `includePackages` parsing to no prefixes
(absent, empty, only separators, or only `excludePackages` set) the agent logs
one ERROR and disables itself entirely, endpoint tier and exporter included.
The ERROR suggests the main class's package when it can be found
deterministically (`sun.java.command`, a jar's `Start-Class` then
`Main-Class`, a module launch's class) and suggests nothing for a main class
in `org.springframework.boot.loader`, `io.ktor.server` or `org.apache.catalina`.
`enabled=false` stays one INFO line. No `*` escape hatch; a broad prefix such
as `com` is accepted.

Landing order, one Opus chunk and one commit each:

1. The refusal and the suggestion: `Agent.start` checks the include list right
   after `enabled`, before anything installs; the main-class resolution as a
   pure, unit-tested function; `AgentConfig.parse` drops its WARNING, since the
   ERROR replaces it; an `Agent.start` test for each refused shape.
2. One meaning for "no include rules": `TypeMatchPolicy.isIncluded` matches
   nothing for an empty list; `JarClassifier` loses its unset branch; the tests
   that relied on an empty list (`LoadedClassSweepTest`,
   `DeflectedClassLoadTest`, `LoadedDependencySweepTest`) set a prefix; the
   testkit's startup-timeout message names `includePackages`; the testkit KDoc
   and README attach examples set it, and the README lists it as required.

Progress:

- Chunk 1 landed: `Agent.start` refuses right after the `enabled` check, and
  `MainClassSuggestion` resolves the main class. Review added two cases the
  brief missed: a `.war` is read like a jar, and `java -m com.acme.shop`, which
  puts the bare module name in `sun.java.command`, resolves through the boot
  layer to the module's declared main class instead of reading the module
  name as a class. Both checked on JDK 22, the boot layer from a real
  `premain`. A launch of the scratch Hibernate program and of a `-jar` with no
  include rules logged the ERROR with the right suggestion and wove nothing.
- Chunk 2 landed: an empty include list matches nothing in
  `TypeMatchPolicy.isIncluded`, traced through every caller (live matcher,
  class-bytes capture, static scanner, sweep, `JarClassifier`, the analyser's
  three scope checks); `JarClassifier` lost its unset branch with nothing
  observable changing, pinned by a new `JarClassifierTest`. Three tests that
  relied on the old default set explicit prefixes that keep them testing the
  same gate, and two that only made sense for it were rewritten to pin the new
  meaning. The testkit's timeout message and KDoc example, and the README's
  attach example and options table, name `includePackages` as required.
  `runDemo` reports as before. No change in `otherlode-collector` or
  `otherlode-server`: `references_recorded` keeps its meaning.

### Generated methods: branches marked, two over-marks closed: landed in both repos

Grilled and settled on 2026-09-23 as an amendment to ADR 0026; `CONTEXT.md`'s
"generated method" was reworded. The question was the branch sites inside a
generated method: the branch `ProbeMeta` passed no `generatedBy`, so a data
class's `equals` was marked while its jumps read as never-hit code the adopter
wrote, which is why `demo-spring`'s `TaxRate` had been a plain class. They are
marked, not dropped: their counts are evidence the way the method's are, and
marking keeps the slot layout. Every consumer already read the mark whatever
the probe kind (the server's `judgeableProbePredicate`, the testkit's
`neverHit`, the stub's partition), so no consumer logic changed.

Checking the shapes with `javap` on Kotlin 2.2.21 found two places ADR 0026
already hid code the adopter wrote, method probes included:

- Under `-jvm-default=disable`, the default up to language version 2.1, an
  interface default method's real body lives in `$DefaultImpls` and the
  interface method is abstract. Every `$DefaultImpls` method was marked, so
  every such body was hidden. Only a forwarder (load arguments, one
  `invokestatic` on the interface, return) is marked.
- A hand-written `equals`, `hashCode` or `toString` on a data class was marked
  with the generated ones. kotlinc emits the generated ones with no
  line-number table and the adopter's with body lines; only one with no table
  is marked. Stripped debug info leaves all three marked.

Only a data class's `equals` and `hashCode` hold branch sites among generated
methods: enum and record methods have none, and `copy$default` is synthetic.
The static scanner calls the same analyser, so `DeclaredMethod` follows both
refinements with no scanner change.

Landing order, one Opus chunk and one commit each:

1. `$DefaultImpls` forwarders only, with fixtures compiled under `disable` and
   the default mode.
2. Data-class `equals`, `hashCode` and `toString` marked only with no line
   table, with a hand-written-`equals` fixture.
3. Branch probes carry their method's mark; the `ProbeLocation.generated_by`
   proto comment and the testkit's `ProbeRef` KDoc follow; an integration test
   asserts `DATA_CLASS` on the branches of a generated `equals` and `NONE` on
   an ordinary method's; `TaxRate` goes back to a `data class` and the Spring
   demo's report stays at four never-hit rows.
4. `otherlode-server`: the `read.go` comments that say a BRANCH probe never
   carries the mark. No logic change.

Progress:

- Chunk 1 landed: `BranchSiteAnalyzer.defaultImplsForwarders` accepts a
  body that loads each parameter once in order, makes one `invokestatic` on
  the interface, and returns; anything else is `NONE`. Every forwarder kotlinc
  2.2.21 emits under `enable` fits, generic, `long`/`double`, accessor,
  `$default` and suspend shapes included. `:fixtures-kotlin-jvm-default-disable`
  compiles the `disable` case and is wired in like the Scala fixtures. Review
  also fixed the proto's `GeneratedBy` comment, which still named every
  `$DefaultImpls` method.
- Chunk 2 landed: `markDataClassMembers` marks `equals`, `hashCode` and
  `toString` only when the method saw no `visitLineNumber`, from a
  `methodsWithLineNumbers` set collected for every method whatever the method
  filter says, so the baseline follows without a scanner change. The new
  `GeneratedPointCustomEquals` fixture holds a hand-written `equals` with two
  conditional sites for chunk 3. Stripped debug info leaves all three marked,
  pinned by a test.
- Chunk 3 landed: the branch `ProbeMeta` passes `analysis.generatedBy(...)`
  like the probes beside it; the mark is outside the layout hash and the slot
  order. `TaxRate` is a `data class` again. `runSpringDemo` measured three
  ways: before, 22 probes and 4 never hit; `data class` without the mark, 34
  and 10, the six extra rows all `TaxRate#equals` branches at line -1; with
  it, 34 and the same 4, with 11 generated and not judged. A single-`Double`
  data class's `hashCode` has no branch site, so only `equals` showed.
- Chunk 4 landed in `otherlode-server` (`e5f53f4`): the `Probe` comment in
  `read.go` and the README say a branch probe carries its method's mark. No
  logic change and no bindings bump, since the proto changed only in comments.

### Generators other than Spring and Hibernate are not recognised

ADR 0029 turns away a runtime-generated class by the markers its generator puts
in the name. Spring's CGLIB and Hibernate are covered, the only two confirmed
against their own source and run end to end here. ByteBuddy's own
`$ByteBuddy$`, javassist's `_$$_jvst` and JDK dynamic proxies (`$Proxy` in a
non-public interface's package) produce the same shape and are not covered.

Each is a rule in `TypeMatchPolicy.isRuntimeGenerated`, a test beside the
others in `TypeMatchPolicyTest`, and an edit to ADR 0029's consequences.
`LoadedClassSweep` follows the rule with no change of its own. What gates the
work is confirming a library's naming against that library rather than
recalling it: none of the three is a dependency of this repo.

Hibernate landed on 2026-09-23. Its guessed marker, `$HibernateProxy$`, was
wrong for 6.6 and 7.4, which name the proxy `<Entity>$HibernateProxy` with
nothing after it; ADR 0029 lists the four kinds of class and each version's
spelling. The suffixes are matched as whole `$`-separated parts of the name,
not as substrings, so an adopter's `Util$HibernateProxyUnwrapper` is kept. A
scratch program driving hibernate-core 7.4.10's own generators under the agent
showed all four kinds woven before the rule and none after.

ByteBuddy, Mockito, javassist and JDK proxy classes were added on 2026-09-26;
ADR 0029's consequences hold the spellings and sources. Three review rounds
narrowed ByteBuddy's and Mockito's markers to a tail of exactly eight or
fifteen letters and digits that does not read as one word, so a body class
kotlinc names after a function (`Power$ByteBuddy$1`) or a one-word class
nested under an adopter's `ByteBuddy` (`Config$ByteBuddy$Settings`) is kept.
ByteBuddy's fixed and caller naming modes, which end the name at
`$ByteBuddy`, are recognised when the JVM's own `net.bytebuddy.naming`
selects them, read once at agent start. Not covered: ByteBuddy's auxiliary
types in any mode (their tail has the shape of a Kotlin local class in a
function called `auxiliary`), an eight- or fifteen-character class
nested under an adopter's `ByteBuddy` or `MockitoMock` whose name is not one
word (`HttpPort`, `V2Config`: turned away, not kept), Mockito's named-module helpers (`$MockitoModuleProbe$`,
`InjectionBase$<n>`), and Weld's proxies, whose naming has not been read from
Weld's source.

The testkit judges a never-hit row once per instance and name, summing the
hits of every copy of a class two loaders defined in that instance; it used to
list each copy's probes on their own. Open from that, found at review:

- `neverHit()` judged each instance on its own hits, and a branch row
  grouped copies by `branch_index`. Landed 2026-10-04 (ADR 0056, agent
  `40f7bc2`): the testkit merges across instances and groups by
  `branch_key`, as the server does.
- The demo's stub collector still judges each class copy on its own. It is
  the demo's printer and loads one copy.

Hibernate's bytecode enhancement adds public, non-synthetic `$$_hibernate_`
methods to the entity class the adopter wrote. Settled 2026-09-26 in ADR 0047:
they are not probed or declared, and a call to one passes through. Open from
that decision:

- EclipseLink (`_persistence_*`), OpenJPA (`pc*`) and Ebean (`_ebean_*`) weave
  methods into entities the same way and are not covered; each needs reading
  from its own source first, and OpenJPA's `pc` prefix is a name a person
  writes, so it cannot be matched by prefix.
- "Persistent field never read" is a possible finding of its own: a column
  stored but never read by application code. It would come from the
  adopter's own field reads, with or without enhancement, not from
  Hibernate's reader methods. Nobody has asked for it.

### Stable branch identity: landed in both repos

`branch_index` is a class-wide ordinal. Sites are numbered in bytecode order
across every method of the class (`OtherlodeInstrumentation.kt`, `BranchSite.kt`),
and dropped sites keep their place. The number holds still when the scope or
a drop rule changes. It does not hold still when the code changes: one new
conditional in an early method shifts every later branch in the class,
including branches in other methods. Nothing on the wire ties branch 14 in
one release to branch 12 in the one before.

`otherlode-server` needs that tie to date a branch across releases. Its fix for
the capped location dates keeps one row per location for the whole service,
but it cannot key a branch that way: a row keyed on `branch_index` would move
an old date onto a new branch and call it dead for years. So branch probes
keep per-instance dates there, capped by scope and marked `dates_capped`,
and `known_for_days` still drops the ones whose capped date is too recent.
See the service-wide dates entry in `otherlode-server`'s STATUS.md.

The server side landed on 2026-09-22 (`otherlode-server` `4ee1026`, its ADR
0025): a keyed branch outcome gets a service-wide date row and groups by
its key across builds, and only keyless outcomes stay capped.

A grilling session settled the design in ADR 0031: each kept branch outcome
gets a branch key, a digest of its class, method, descriptor, condition
fingerprint and outcome, sent as `branch_key` on `ProbeLocation` beside
`branch_index`. Sites that share a fingerprint in one method get no key, and
neither does any case the analyser cannot fingerprint with confidence.

Landing order:

1. ADR 0031 and the glossary terms (branch outcome, branch index, branch key).
2. Stack-depth tracking and the condition fingerprint in `BranchSiteAnalyzer`,
   stored on `BranchSite`.
3. The key itself, with collision handling and switch case keys, carried
   through `ProbeMeta` into the manifest, and the proto field.
4. Tests from v1/v2 fixture pairs: each kind of edit keeps or changes the key
   as the ADR says, plus the collision and inlined-copy cases.

Progress:

- Step 1 landed with ADR 0031 (`7de11e8`, amended in `46269de` to name
  local variables from the `LocalVariableTable`).
- Step 2 landed: `ConditionFingerprinter`, a second read of the class
  bytes beside the analyser, gives every tracked site a fingerprint or
  null, and every switch its case keys in the rewriter's outcome order.
  Stack depth is tracked by hand, since ByteBuddy's shaded ASM has no
  `AnalyzerAdapter`. Review found that a condition holding its own
  branches (ternary, elvis, the earlier `&&` operand) empties the stack
  partway, so its window starts late and an edit before that point keeps
  the key; ADR 0031's consequences say so.
- Step 3 landed: `BranchKeys` applies the collision rule and derives each
  kept outcome's key (SHA-256 of a `v1`-tagged text, first 16 bytes as
  hex), `ProbeMeta` and the manifest carry it, and `ProbeLocation` has
  `optional string branch_key = 18`. The layout hash is pinned by a test
  and unchanged. The testkit's `ProbeRef` exposes `branchKey`. `buf` was
  not available locally; the change is a new field only, and CI's `buf`
  workflow passed on it.
- Step 4 landed: v1/v2 fixture pairs under `com.example.target.keypairs`,
  renamed to one class name with `ClassRemapper`, prove each edit ADR
  0031 names keeps or changes the key, plus an end-to-end check through
  the real transform in two class loaders. No main-code bug turned up.
  Review replaced the ternary pair, which only put a statement between
  the ternary and the condition, with one where the `if` expression sits
  inside the condition: an edit to its true arm keeps the outer key and
  an edit to its false arm changes it, as the Consequences say.

The server work this unblocked landed on 2026-09-22 (`otherlode-server`
`4ee1026`, its ADR 0025, BSR `15053c3b6627`): a keyed branch outcome gets a
service row and groups by its key across builds, and a keyless one stays capped,
with never-hit under `known_for_days` and stale-hit reporting what they hide as
`capped_hidden`. Nothing here is open.

Two things a fresh cloud session needs for this repo's build. Maven Central
has answered 429 through the sandbox proxy, and a session-local Gradle init
script pointing at Google's mirror of it
(`maven-central.storage-download.googleapis.com/maven2`) worked once the
user approved it. And the `ktlint` CLI the chunked-build command runs is not
preinstalled; the 1.5.0 release binary from GitHub works.

### Follow-ups the branch-probe round left open

Each is recorded rather than started. They are small and wait for a reason
to touch the code.

- The true-but-uninteresting classification is decided in ADR 0046 and its
  agent chunk is built: see "Routine outcomes" above. What it leaves open is
  listed there.
- Kotlin `value class` `-impl` methods and kotlinx.serialization's generated
  output as further `GeneratedBy` values.
- javac's string-switch and try-with-resources shapes, which were zero in
  every Kotlin corpus measured and wait for a Java corpus to be worth
  recognising.

### Telling a failed load apart from an unreferenced class

A class woven and registered that the JVM never defined is withheld from the
manifest (ADR 0028), so a collector diffing the static baseline against it
calls the class never loaded, which is true. What it cannot say is why. "Never
loaded because nothing referenced it" is dead code to delete; "never loaded
because loading it failed" is a deployment to fix, and only the agent can tell
them apart.

Closing it means a bucket on `ProbeManifest` beside `unreported_classes`: a
proto field, a registry bucket, a chunk weight, codec work and a collector
change. That was judged out of proportion to a population nobody has shown
exists yet, so the agent logs a WARNING per class and nothing goes on the
wire. Build it when a report from a real service shows these classes turning
up. If they never do, this dies honestly.

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

### Endpoint follow-ups not started

- Static analysis of registration call sites, to declare an endpoint whose
  registration the agent never sees at runtime.
- Per-framework disable flags. One `endpointsEnabled` switch and the
  self-disabling modules cover everything known so far; this is only worth
  building if an adopter needs to turn one module off by hand.

OpenAPI as a source of endpoints was set aside (ADR 0017). A contract-diff
feature built on it is parked in `otherlode-server`'s STATUS.

### Code that only tests call

Designed on 2026-10-01 in a grilling session with Luke: ADR 0050 here,
server ADR 0047. The server's STATUS tracks the finding and its chunks.
The agent's part is the `testRun` option, which puts `test_run` on every
payload's `ResourceAttributes` and names the environment `test` when no
source names one, plus the README section on running the agent in a test
JVM. A test run's shutdown waits up to 15 seconds for its scan, after the
final flush. The demo's
stub collector answers a test run's payloads and keeps nothing from them.
The testkit ignores the flag.

The collector carries and logs the flag (collector `3c2f22a`), and the
server names the tests (server `3d5de82` and `426ec92`). Checked end to
end on 2026-10-01: `runDemoStack` as the production run, then a Java test
under the agent with `testRun=true`, through a collector redacting all
literals. `Money#constructor(int, int)` read called only by tests, with
both tests named, one of them through its lambda.

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
not for the exact-body rules (see "Deep review of 2026-10-03"). Branch level will not
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


### A module disabled after it has already declared routes

A module that throws is switched off for the rest of the process, and its
dispatch advice stops counting from then on. Routes it declared for classes
it handled successfully keep their manifest rows, so their counts freeze
wherever they were: a route the framework still serves reads as never called
once its count sat at zero.

Settled in favour of the collector, not the agent. The agent keeps reporting
what it saw: the endpoints it declared, and the module it disabled, with the
time it happened. A collector joins the two, since a module's name is the
same string its endpoints carry as `framework`, and leaves every endpoint of
a module disabled everywhere it is known out of its dead-code claims, the
way it leaves out an inline probe. Withdrawing the endpoints instead would
need a tombstone on the wire and would throw away that the route existed and
was served at all, which is true whatever happened to the module later. Same
shape for a module disabled through the runtime `declare` walk, which
predates the transform-time staging.

### A named class implementing a framework interface reads as an uncalled root

ADR 0024's known gap. A call that leaves scope and comes back, a framework
invoking an adopter's class, shows as no edge, so such a class's never-hit
method appears as a cluster root with no caller. Lambdas are covered, since
a body is reached from its creator, and the endpoint join carries the route
to a handler class, which is what the demo's `PromoHandler` shows. What is
left is the shape with no endpoint beside it. If uncalled roots in a real
report turn out to be mostly this, recording out-of-scope callees is the
answer; until then it is a labelled root rather than a wrong one.

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

### Deliberate v1 boundaries

Not gaps, and not on anyone's list: static attach only (ADR 0013), no
redefinition of a woven class whose class file changed (ADR 0005, which
accepts one whose class file did not), the
static scan not opening `BOOT-INF/lib` nested dependency jars, the
classpath blind spot for app-server, OSGi and plugin-loaded deployments, and
include rules being required, with no `includePackages=*` to ask for every
class (ADR 0033).
Each has its own section in `CLAUDE.md` with the reasoning and what it would
take to change.
