# Status

Working notes on what is in flight and what is parked. Not a changelog; git
history covers that. `docs/adr/` holds one record per decision, `CONTEXT.md`
the glossary, `docs/site/` the customer docs, and `CLAUDE.md` the rules a
change follows.

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
   - Not seen in these runs, landed on 2026-10-06 in all three repos: a
     named class implementing a framework interface read as an uncalled
     root, and a class that failed to load read as never loaded. See the
     closed entries "Outside callers and classes that failed to load" and
     "Classes that failed to load, made certain". kotlin-stdlib
     always reading as used, through `kotlin.Metadata`, is closed as true:
     a Kotlin service cannot drop it, and a Java service that pulls it in
     is judged on its real use, since Java classes carry no
     `kotlin.Metadata`.
   - An unread body reads as the adopter's code, found after item 3 landed:
     current Scala patch releases make every case class's `hashCode` dead code,
     and kotlinc 2.1 and earlier a never-hit stub per interface default method.
     Settled on 2026-10-03 (ADRs 0054, 0055) and landed on 2026-10-04 in all
     three repos: agent `1ccb7a5` to `a985ad1`, collector `cdf423b`, server
     `b200bc2` and `6dbc56a`. Compiler output the agent has not read is an
     unread shape, never a finding. The closed entry "An unread body is an
     unread shape" has each step; the shapes still unread are in Parked.
   - Naming, after release: `Tariff$Companion`, `Cc$` and `Driver$` show
     their JVM names in the UI, and Scala signatures read with Java types and
     `x$0` parameter names. A data class property's getter reads as never
     hit when only `copy`, `toString` and the class's own methods read the
     field; that is true, and noisy.
3. **Another agent ahead of or behind this one.** Done on 2026-10-03;
   ADRs 0052 and 0053. With JaCoCo ahead, every exact-body result is read
   from JaCoCo's output; with this agent ahead, which is Gradle's default
   for an adopter's test task, JaCoCo's report loses every woven class. The
   plan is the closed entry "Another agent ahead of or behind this one". It
   sits here because the testkit's first report is wrong and the adopter's
   coverage report breaks.
4. **Settle the one-way doors before anything is published.** Settled on
   2026-10-04 in a grilling session (ADRs 0056 and 0057, amendments to 0016,
   0045 and 0054, collector ADR 0005, server ADR 0054) and built the same
   day in all three repos; the closed entry "One-way doors settled before
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
   - Before the first release of each: the collector's GHCR package made
     public, and the org secret `OTHERLODE_DEV_DOCS_TOKEN` visible to this
     repo and the collector, or the release's docs job skips the sync to
     otherlode.dev with only a notice.
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
   - Rehearsal 1 passed on 2026-10-06 (tag `rehearsal/1` at `3d3dd0e`, run
     37386314582): signed with the release subkey, `verifyPublication`
     passed, deployment `33c6eaa9-4638-4488-b156-77903a9f5b8d` reached
     VALIDATED within seconds, and the API drop removed it (the status query
     afterwards found nothing). Not yet known: whether a dropped deployment
     frees its version; a `rehearsal/1-again` tag answers it.
   - Chunk 4 landed on 2026-10-06 in the collector (`304718d`): a `v*.*.*`
     tag runs a guard, its CI, a multi-arch image (`linux/amd64`,
     `linux/arm64`, cross-compiled) pushed to
     `ghcr.io/otherlodehq/otherlode-collector` as `X.Y.Z`, `X.Y` and `latest`
     with an SBOM, provenance and a build-provenance attestation, then a
     published GitHub release naming the digest; `scripts/release.sh` builds
     before each commit; the redaction WARNING and README say "the latest
     collector". One Opus review; its findings are in the commit. The
     collector's `Release tags` ruleset and release immutability are on.
   - Left in item 6: cutting collector `v0.1.0` and agent `0.1.0`, then
     making the GHCR package public. Cutting them is a deliberate action
     for a future session, Luke's call (2026-10-06): the agent's needs its
     `## [0.1.0]` changelog section written, and the collector's must exist
     first, since `release.sh` refuses an agent release until it does.
   - Luke's steps, needed before the rehearsal. Done on 2026-10-05: the
     `releases@otherlode.dev` group (Google Workspace, external posting on),
     the signing key (fingerprints and expiry under "Reminders for expiring
     keys and credentials") on keys.openpgp.org and keyserver.ubuntu.com, the
     Portal account (`luke@otherlode.dev`), the `dev.otherlode` namespace,
     verified by its TXT record on the apex `otherlode.dev`, and a Portal user
     token. Done on 2026-10-06: the `release` environment and its secrets, the
     tag ruleset, immutable releases.
   - The flaky `CodeSourceClassFileTest` is fixed (2026-10-06). A race in the
     test, not the agent: with the real daemon warmer, the warm-up thread can
     finish before `isWarmed` re-checks the state, so the first read
     legitimately answers bytes, and the two tests asserted null. Production
     is unaffected, since the key is marked warm only after the warm-up
     thread opened the location outside any transform. The tests hold the
     warm-up's open until the first read has answered; reproduced on demand
     with an injected delay, then 0 failures in 80 stressed runs over JDK 17,
     21 and 25. The class KDoc now says the first read can answer bytes.
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
7. **Measure the overhead.** Grilled on 2026-10-04; the closed entry "Runtime
   overhead". Before release: the PetClinic macro
   harness with its headline and ceiling numbers in the README, the hot-path
   JMH suite, the deterministic CI checks, and, moved before release on
   2026-10-04, the code-size guard, the flush and registry benchmark and the
   nightly schedule.
8. **Weave everything that can be woven, and never crash an adopter's
   class.** Grilled on 2026-10-04; the closed entry "Probe arrays, validation and
   placeholders". A woven class could throw
   `NullPointerException` where the original ran (ADR 0060), and 312 of the
   ceiling run's 4462 matched classes were skipped, most of them needlessly
   (ADRs 0058, 0059).
9. **Startup and heap.** Grilled and landed on 2026-10-05; the closed
   entries "The agent's startup and heap" and "`DECORATE` instead of
   `REBASE`, and the entry probe in ASM". On the runner (`8139271`) the
   headline's startup overhead went from +76% to +22.4%, inside the 25%
   target, and the ceiling's retained heap after delivery from +117.5 MB to
   +26.5 MB locally. The ceiling's one-core startup on the runner improved
   far less, from 64.0 s to 58.2 s with the agent (15.2 s and 18.2 s
   without), than it did pinned locally in Docker (21.5 s over 6.8 s on one
   CPU). Nobody has looked into why. The `DECORATE` round (`b1dec7d` to `a3c4330`) landed after that
   run. The overhead runs on `master`, nightly and by hand, have measured it
   since 2026-10-07 (the latest passing run is 38537477681, on 2026-10-14),
   but nobody has read their numbers: `docs/site/overhead.md` and
   `benchmark-overhead/README.md` still quote `8139271`, so startup is likely
   overstated. Left before `v0.1.0`: read those runs, starting with the
   ceiling's one-core startup, then re-run on the commit to be tagged and
   update both pages.

10. **Docs for the agent and the collector.** Added 2026-10-05. Where
    they live was settled in otherlode.dev ADRs 0001 and 0002: each repo's
    `docs/site/`, synced to the site per release. The agent's fifteen pages
    landed on 2026-10-07 (`e43d82b`), each claim checked against the code;
    the closed entry "Customer docs round: follow-ups" has what it turned up.
    Still to write: the collector's pages, and the cross-product overview
    and quickstart in otherlode.dev's `start/`.

11. **Server storage: retention, expiry and class records.** Added
    2026-10-08 after a grilling session (server ADRs 0060 and 0061). Every
    run stored its own manifest and nothing expired, so storage grew with
    every restart. Settled: a per-tenant retention period, a daily expiry
    that never removes what a dead-code claim rests on, and class records
    shared by the runs of one service. The schema is collapsed for the new
    Cloud SQL instance Luke brings up before go-live. The server STATUS
    entry "Storage: retention, expiry and class records" has the build
    order. It does not block publishing the agent, but it blocks the first
    customer.

12. **Server read performance at realistic scale.** Added 2026-10-09. A
    benchmark of one service with 2,000 classes and 50 runs put the report
    at about 6.5 minutes and the call graph at about 4, in both the old and
    the class-record layout, and version-filtered reads timed out. The
    server STATUS entry "Read performance at realistic scale" has the
    numbers. To grill before the first customer.
13. **A linked jar's `Class-Path` in the dependency listing.** Added
    2026-10-14. The entry "The dependency listing reads a linked jar's
    `Class-Path` from the link" has the fix. Its reported path can change
    freely only until the release.

After release: naming polish (`this$0`, the demo printer), the server's
performance-only deferrals, gzip, a collector config file, agent-level
redaction (parked in the server's STATUS, item 18, with its trigger), and
the edge cases under "Parked" below.

## TODO

Entries whose work is done are removed. The ones the checklist above names,
and every entry closed on 2026-10-14, are in `git show e54e33b:STATUS.md`.

### Class listing and flush interval: landed, cost not measured

Decided on 2026-10-14 with Luke, in a grilling session across the three
repos, for server ADR 0066 (removed code).

1. `ClassListing` on the wire, sent to `/v1/otherlode/class-listing` in
   chunks like the baseline, with the include and exclude rules it
   applied. `StaticBaseline` gains the same rules field.
   `ResourceAttributes` gains `flush_interval_seconds`. Landed: the schema,
   the Kotlin models and the codec. A review sharpened the proto comments
   a Go reader depends on: the prefix rule (equal, or continued by `.` or
   `$`), zero meaning an unknown interval, absent rules, and a listing
   with a hole never being sent (added to ADR 0070).
2. List names from the baseline's roots and from nested `BOOT-INF/lib`
   and `WEB-INF/lib` jars, without parsing. Reuse `JarContents`. Landed with
   steps 3, 5 and 6's agent side. Three review rounds made the lister
   read the classpath the way the system loader does: symbolic links
   resolved, `Class-Path` parsed as `URLClassPath` parses it, agent jars
   read, exploded Spring Boot images read by the launcher layout,
   multi-release versioned-only classes listed, and no listing for an
   empty class path, an empty element, a module path or a linked image.
   Known gaps, all rare, all listing less than the JVM loads:
   - A nested jar with junk between its entries: `ZipInputStream` stops
     at the junk with no error, while Boot reads the central directory.
   - JDK 17's `META-INF/INDEX.LIST`, which can name jars outside
     `Class-Path` (JDK 18 dropped it).
   - Boot `PropertiesLauncher` `loader.path` folders (documented as a
     blind spot; the server's coverage rule limits the damage).
   The dependency listing shares the `Class-Path` parser but still
   resolves it against a symbolic link's own path: see the next entry.
3. `classListingEnabled`, on by default. Landed.
4. Measure the listing's cost on demo-spring and a large classpath. Open.
5. Update `configuration`, `data-sent`, `overhead` and `classes`. Landed,
   with `troubleshooting` and `testkit-api`.
6. Collector: the route and the bindings bump. Landed in the collector
   as `69d5a58`.

### The dependency listing reads a linked jar's `Class-Path` from the link: to fix before release

Found on 2026-10-14 in the class listing's review. `StartupClasspathLister`
passes a classpath entry to `listJar` as its absolute path, so
`classPathEntries` resolves the manifest's relative `Class-Path` entries
against the folder that holds the symbolic link. The JDK's system loader
reads the entry through its canonical path, so it resolves them against
the folder of the jar the link points to. `ClassLister` already does
this.

It matters only when a classpath jar is a link to a jar in another
folder and its manifest has relative `Class-Path` entries, as in a
symlink tree such as Bazel runfiles. A link to a jar in the same folder,
as distros lay out `/usr/share/java`, resolves the same either way. When
it does matter, a jar the JVM loads is left out of the listing with no
sign, or a same-named jar beside the link is listed with its version.
It cannot cause a false removal (server ADR 0066), since every build of
the service resolves the same wrong path and a dependency is removed
only after an earlier listing named it.

Fix: resolve `Class-Path` against the canonical file, as `ClassLister`
does. Decide whether the reported path stays the link's (what the
customer configured) or becomes the target's; the reported path is free
to change only until the first release. Test with a linked jar in
another folder whose manifest names a sibling jar.

### scalac 3.10.0 drops the fields that name an enum's parameterised cases: to fix

Found 2026-10-12 by the compiler canary, which has failed on 3.10.0 since
its run of 2026-10-05. In 3.9.0 an enum's companion (`Shape$`) holds a
static field per parameterised case, `Circle: Shape$Circle$`. 3.10.0 no
longer writes it. The case classes and their companions are still there,
singleton cases keep their `ACC_ENUM` fields, and every method body is the
same as in 3.9.0.

`enumCases` in `ScalaGeneratedMethods` builds an enum's cases, in ordinal
order, from those fields. Over a 3.10.0 class it finds only the singleton
cases, so:

- A parameterised case's `ordinal()` gets no outline (`ordinalOf` is -1),
  so it is marked as the adopter's own code. It is not even an unread
  shape, and it can read as dead code. This holds whether or not 3.10.0 is
  on the read list, and with no `.tasty` file.
- The companion's `fromOrdinal` gives the singleton cases the wrong
  ordinals (0 and 1 in place of 2 and 3) and reads as an unread shape.
  While 3.10.0 is off the read list this is only counted, never a finding.

The matrix run with 3.10.0 added to the read list fails on `Shape`,
`Gapped`, `Op` and `Term` in `Enums.scala`: 8 mark differences, and
`Shape$.fromOrdinal` and `Gapped$.fromOrdinal` as extra unread shapes.

To do: find a parameterised case and its ordinal some other way when the
companion has no field for it. One source is the case class's constant
`ordinal()` together with the `$new(ordinal, name)` calls in the
companion's `<clinit>`. Write the failing test first. Once
`ScalacMatrixTest` passes, add 3.10.0 to `scala3-read-releases.txt` and to
`otherlode.matrix.scalac`, and the canary goes green. 3.10.0 also writes a
parameterised case's `equals` with an extra local, which the rules already
read.

### An adopter class in a named module cannot reach the probe arrays: to look at

Recorded in the probe-array round (ADR 0060) as a gap that predates it, and
tracked nowhere else. A woven class reaches its counts through
`OtherlodeProbeArrays`, in the boot loader's unnamed module
(`ProbeArrayMembers`, `ProbeArrayForm`). A class in a named module reads
only the modules it declares, so it needs a read edge to that one. The
endpoint tier adds the edge for its own advice through `redefineModule`
(`EndpointInstrumentation`, `LambdaFactoryHook`). The method tier adds
none for adopter modules. No test weaves a class in a named module.

If the edge is missing, the woven class fails where the unwoven one ran,
which breaks the weaving invariant. To do: write the failing test first,
with a fixture module on the module path under the include rules, then
add the read edge when such a class is first transformed.

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

### A class the verifier rejects reads as never-hit code: to decide

Found on 2026-10-06 while making failed classes certain (ADR 0066). The
JVM verifies a class after it defines it, so a class the verifier rejects
is defined and in `getAllLoadedClasses()`. It is confirmed by name, never
named failed, and its methods can never run, so they read as never hit.
That is a dead-code claim about code whose deployment is broken. Raised
with Luke and recorded as a follow-up. A fix needs a signal that a class
failed to link or initialise without loading anything, such as a probe in
`<clinit>` that never fires beside a `NoClassDefFoundError` the agent
cannot see, so it wants its own grill.

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
code. The entries they came from are in `git show e54e33b:STATUS.md`.

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
- The OTel bridge duplicates a route under a servlet context path:
  OpenTelemetry's route includes the path and a framework module's identity
  does not.
- The OTel bridge records OpenTelemetry's `HTTP` verb for an unknown method
  as an endpoint of its own.
- Spring's annotation-mapped side counts a CORS preflight as a hit of the
  `GET` it asks about, since its dispatch advice sees no request.
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

- A `byte-buddy-agent` jar flat in an exploded war's `WEB-INF/lib` is turned
  away by the agent-jar rule and reads as no dependency rather than as one.
- The testkit's `awaitSettled` counts two delta batches after its start,
  which assumes one batch per flush, so a flush split into several batches
  can wake it before the hit totals settle. ADR 0036 fixed the same flaw in
  `dependency()`.

Overhead and memory:

- The JMH micro suite runs in no workflow, so it has no leg on any JDK.
- Overhead results live in each run. Committing them to a results branch
  waits until a trend view across months is wanted.
- Condition text is not interned (`MetadataCompaction`), a possible saving.
- A Scala 3 enum costs one class read per case for each enum class
  transformed, which grows with the square of the case count: about 6,900
  reads for an 80-case enum, once, at load. Not measured.

Ideas nobody has asked for:

- A server-side counterpart to `VisibilityFloorTest`, run over fixture
  payloads.
- A "persistent field never read" finding: a stored column no application
  code reads. It would come from the adopter's own field reads and be
  mostly the server's work.

### Compiler shapes not yet read, after release

What the compiler canary and the reviews found that the agent does not
read. Each reads as an unread shape, never a finding, unless it says
otherwise. ADRs 0054 and 0055 hold the rule; the closed entry "An unread
body is an unread shape" has the history.

- Scala 3: a Java-compatible enum (`extends java.lang.Enum`), a local enum
  (it gets no outline), backticked case names, an enum that overrides
  `productPrefix`, and two case names with the same hash. An enum inside a
  class reports scalac's `ordinal(E)` as the adopter's own on a read
  release. Scala 3.3 at its default Java 8 target writes `valueOf` and
  `fromOrdinal` with a `StringBuilder`, which reads as unread shapes until
  it is read (ADR 0055, amended). 3.10.0 is its own TODO entry.
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
