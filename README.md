# Otherlode

A Java agent that instruments a running JVM application to find code that's
reachable but never actually exercised at runtime: unused endpoints, methods
that are never invoked, and conditionals that only ever take one branch.

Attach it with `-javaagent`, run your app, and it reports which of those
probes never fired.

Customer docs live in [`docs/site/`](docs/site/), synced to otherlode.dev. This
README covers building and developing the agent.

## How it works, briefly

- Method-level probes (ASM, woven through ByteBuddy's `AgentBuilder`) catch
  unused methods and classes.
- Endpoint modules for Spring MVC, Ktor, JAX-RS and the JDK's `HttpServer`
  count each endpoint where the framework matches a request to it, and list
  the endpoints it registered, so one never called shows up.
- Branch-level probes (ASM, woven in the same transform pass) catch
  conditionals and switches that only ever take one path.
- The agent batches hit counts and pushes them to a collector on a fixed
  interval (OTLP-style: delta batches plus an incremental probe manifest)
  instead of exposing them for scraping, so a collector can aggregate
  results across every instance in a fleet.

## Build

```
./gradlew build
```

Produces a shaded agent jar at `build/libs/otherlode-agent-<version>.jar` with
ByteBuddy, protobuf, the Kotlin standard library and the agent's own wire
classes relocated, so it won't collide with copies already on the target
application's classpath.

### Benchmark the transform-time analysis

```
./gradlew jmh
./gradlew jmh -Potherlode.benchmark.corpus=demo
./gradlew jmh -Potherlode.benchmark.corpus=demo,scala
```

A JMH benchmark in `src/jmh/kotlin` times the branch analyser over five
corpora of real class files: `demo`, `demo-spring`, `scala` (both Scala
fixture modules), `spring-webmvc` (the jar `demo-spring` resolves) and
`ktor-server-core` (the jar `endpoints-ktor-3` tests against). The first command runs all five, about 40
seconds each. `-Potherlode.benchmark.corpus` picks one or more by name. The
score is the average time to analyse the whole corpus once. Each run prints
the corpus's class count, so the time per class is the score divided by that
count. Results go to `build/results/jmh/results.txt`.

## Attach it to an app

Attach the agent with `-javaagent`, passing `includePackages` at minimum. See
[`docs/site/attach.md`](docs/site/attach.md) for the flag, Gradle, Spring Boot,
Docker and Kubernetes. [`docs/site/configuration.md`](docs/site/configuration.md)
lists every option, where a value comes from (argument string, then system
property, then environment variable) and how the agent reads OpenTelemetry's
settings.

Otherlode needs somewhere to send data to. See the `demo` module below for a
minimal stub, or point it at a real collector.

## Try the demo

```
./gradlew :demo:runDemo
./gradlew :demo:runSpringDemo
```

Runs a stub collector, an instrumented demo server (`/checkout`, always hit
one way; `/promo`, never called), and a client that drives the server, all as
separate JVM processes. On shutdown, the stub collector prints a report of
probes that were never hit and any classes it had to skip. `runSpringDemo`
does the same for the Spring Boot fat-jar demo.

A never-hit branch prints as its condition, the result that never happened,
and the lines that run only through that result. Its branch index stays in
brackets at the end. A condition reads the way the code just after the check
sees it, which for a plain `if` is the condition the source wrote. The demo's
two checkout conditions print like this:

```
NEVER HIT: handleCheckout (DemoServerMain.kt:71) `System.getenv("ENABLE_LEGACY_DISCOUNT") == "true"` was never true, only path to DemoServerMain.kt:72 (instance 17e3afc3-76f1-435c-b30a-5e594350530f, class 0, probe 10) [BRANCH branch#1]
NEVER HIT: handleCheckout (DemoServerMain.kt:78) `discounted > 100.0` was never true, only path to DemoServerMain.kt:79, partly to DemoServerMain.kt:92 (instance 17e3afc3-76f1-435c-b30a-5e594350530f, class 0, probe 12) [BRANCH branch#3]
```

A condition carries the string literals the source compared against, such
as `"ENABLE_LEGACY_DISCOUNT"` above, and so does a string `when` case. The
agent sends them as written; it has no redaction setting of its own. The
collector can replace them before they leave your network (collector ADR
0001), so a deployment that needs redaction sends through a collector with
it switched on. An agent that posts straight to a backend sends literals in
clear.

Some outcomes are real but not worth a person's time when they never run.
The agent reads each outcome's path in the bytecode and marks it with a
routine kind (ADR 0046): the null side of a null check that calls nothing
(`default never used`), a path that only builds and throws an exception
(`only throws`), or the exception-path copy of a `finally` body (`finally
copy`). The report keeps these out of the never-hit list and the headline,
and lists them apart under `ROUTINE OUTCOMES`. The demo client always sends
a total, so the null checks in `totalParam` never see null and print there:

```
ROUTINE [default never used]: totalParam (DemoServerMain.kt:136) `value != null` was never false, guards no code of its own (instance 4aef3090-5d33-4ab7-897e-f6170b0ab9a5, class 0, probe 17) [branch#14]
```

A top-level function prints with its file, as `handleCheckout
(DemoServerMain.kt)`, rather than as a member of `DemoServerMainKt`, the
class kotlinc makes for the file. The agent reads that from the kind kotlinc
writes into each class's `kotlin.Metadata`, never from the name (ADR 0041).
The demo also has two files that `@file:JvmMultifileClass` joins into one
class, `DemoText`. That class holds only forwarders, one per function, and
the code lives in one part class per file. The checkout handler calls
`checkoutGreeting` through the forwarder, and the agent follows the call to
the part, so the forwarder is marked generated and never reported. Nothing
calls `farewellNote`, so its part never loads and prints by its file:

```
NEVER LOADED: DemoTextFarewell.kt (methods: farewellNote)
```

Some findings are about a whole class, not a method in it (otherlode-server's
ADR 0034). `main` names `AuditLog` and `ReceiptPrinter` through class
literals, which load a class without initialising it. `AuditLog` is an
object, so its static initialiser is where its one instance is made; that
never runs, so the class is never initialised. `ReceiptPrinter` has no
static initialiser, but it declares an instance method and none of its
constructors ran, so it is never instantiated. The report prints both, and
the never-hit list leaves out the methods that can only run through them:

```
NEVER INITIALISED: com.example.demo.server.AuditLog (methods: constructor, record) (instances loading: 1)
NEVER INSTANTIATED: com.example.demo.server.ReceiptPrinter (methods: constructor, print, print$lambda$0) (instances loading: 1)
```

A static initialiser is never a row of its own, and a constructor is a row
only as an unused overload: one that never ran while another constructor
of its class did. The checkout handler builds every `Money` from pence, so
`Money`'s pounds-and-pence constructor is one. `Price` has one constructor
with `@JvmOverloads`, and the checkout always passes its scale, so the
extra overload kotlinc adds never runs either. The agent marks that
overload generated, so it is never reported. Constructors print with their
parameter types:

```
NEVER HIT: com.example.demo.server.Money#constructor(int, int):10 [CONSTRUCTOR, unused overload] (instance 17e3afc3-76f1-435c-b30a-5e594350530f, class 5, probe 1)
```

The last report groups those never-hit probes into unreached clusters: a
root that's either reached from code that does run, never called at all, a
branch outcome that never ran in a method that did, or a class that holds a
class finding, plus every never-hit method beneath it whose only callers are
also in the cluster. `/promo`'s handler calls a helper that calls a
repository method neither the handler nor anything else ever reaches, so it
prints as one four-method cluster: the handler, its helper, and the
repository's method and constructor. The repository class never loads at
all in this demo, and the cluster holds every method of it, so it prints
once as a whole class with its finding. A call into a class counts as a
call into its static initialiser, which is why the whole class follows the
handler into the cluster; the initialiser itself is never listed or
counted. The handler is a named class, so the endpoint record names its
`handle` method and the route is printed beside the root. Nothing in the
demo's own code calls `handle`, only the server. The method overrides
`HttpHandler.handle`, and `HttpHandler` is outside scope, so the root reads
as called from outside scope, not uncalled. Code outside scope may call it,
which makes it weaker evidence for deletion:

```
UNREACHED CLUSTER: root com.example.demo.server.PromoHandler#handle (called from outside scope: overrides HttpHandler), 4 methods, 1 never-loaded classes routes=[* /promo]
  com.example.demo.server.PromoRepository (whole class, never loaded, 2 methods)
  applyPromoCode (DemoServerMain.kt)
  com.example.demo.server.PromoHandler#handle
```

`/checkout` is registered the other way, as a function reference that
Kotlin converts to the `HttpHandler` interface through `invokedynamic`.
That handler is a hidden class with no stable name. The agent records
which method it calls as the JDK spins it (ADR 0035), so the two shapes
sit side by side in the endpoint report: `* /promo` names
`PromoHandler#handle`, and `* /checkout` names
`DemoServerMainKt#handleCheckout`.

A second cluster starts at the branch the demo never takes. The untaken
side of the checkout handler's `if` constructs `LegacyDiscountCalculator`,
calls its `apply`, and reads a static field of `LegacyRates`, which runs
that object's static initialiser and so its constructor. Each of those
calls sits behind that one outcome, so the outcome is the root, printed the
way the never-hit report prints it, followed by the method that holds it.
Deleting that side of the `if` removes both classes whole:

```
UNREACHED CLUSTER: root `System.getenv("ENABLE_LEGACY_DISCOUNT") == "true"` was never true, only path to DemoServerMain.kt:72, in handleCheckout (DemoServerMain.kt:71) (untaken outcome), 3 methods, 2 never-loaded classes routes=[* /checkout]
  com.example.demo.server.LegacyDiscountCalculator (whole class, never loaded, 2 methods)
  com.example.demo.server.LegacyRates (whole class, never loaded, 1 methods)
```

A class that holds a class finding roots a cluster of its own when nothing
calls it, or when a method that ran does. It is listed only when the
cluster holds more than the methods the finding folds, since the class
findings report already names the class. A lambda body goes with the
methods that create it: `ReceiptPrinter`'s `print` hands a lambda to
`joinToString`, and kotlinc compiles that lambda to a static method of the
class, which folds into the class finding with `print`. So neither
`AuditLog` nor `ReceiptPrinter` roots a listed cluster.

A never-hit method called from a method that ran, and not from behind an
untaken outcome, is a root "reached from hit", and the report names the
callers after it. The checkout handler also calls its response helper
through a function reference; the class the compiler generates for that
reference is reached from the handler and never appears in a cluster.

## Run the demo against a real collector

```
./gradlew :demo:runDemoStack
./gradlew :demo:runSpringDemoStack
./gradlew :demo:runShapesStack
```

Runs the same instrumented demo server and client, but against a real
collector instead of the stub, then prints what the backend behind it
reports. `runSpringDemoStack` does the same for the Spring Boot fat-jar
demo, as service `otherlode-spring-demo`. `runShapesStack` runs three short
programs one after another, each as its own service: `otherlode-shapes`
touches Java anonymous classes and a lambda, a companion object, a data
class and a suspend function, each with a part that never runs, and
`otherlode-fixtures-scala2` and `otherlode-fixtures-scala3` call some of the Scala
fixture modules' `Driver` methods and leave the rest. Each prints: probe and class counts, the never-hit probes, the never-loaded,
never-initialised and never-instantiated classes, and the unreached
clusters. It expects a collector at `http://localhost:4319` and the
otherlode-server read API at `http://localhost:4320`, which is what
otherlode-server's `docker compose --profile stack up --build` provides. Every
address and credential can be overridden:

| Property | Default |
|---|---|
| `-PotherlodeEndpoint` | `http://localhost:4319` |
| `-PotherlodeAgentToken` | `local-stack-agent-token` |
| `-PotherlodeServerUrl` | `http://localhost:4320` |
| `-PotherlodeServerReadApiKey` | `otl_stack-read-key-for-local-dev-only`, the stack's read key; the demo only reads the server's API. |
| `-PotherlodeServiceVersion` | `stack-demo`, `spring-stack-demo` for the Spring demo, `shapes-stack-demo` for the shapes run |

The defaults match the compose stack's own development defaults, so with
the stack up it works with no arguments. Each run registers as a new
instance, and the server keeps everything it has seen, so the report
covers every run of that service and version so far; pass a fresh
`-PotherlodeServiceVersion` to start a clean slate.

The demo runs in the unspecified namespace. To run it in a named one, set
`OTHERLODE_SERVICE_NAMESPACE` in the environment of the Gradle command; the
tasks pass it to the demo server, and the report names the namespace.

## Test your app against the agent

The `testkit` module is an embeddable collector for your own tests. See
[`docs/site/testkit.md`](docs/site/testkit.md) for the setup and
[`docs/site/testkit-api.md`](docs/site/testkit-api.md) for every query and wait.

The module isn't published yet. Build the jar with
`./gradlew :testkit:shadowJar` and put it on your test classpath. It bundles the
wire classes and protobuf-java under `dev.otherlode.testkit.shaded`, so the only
dependency it brings onto your test classpath is kotlin-stdlib, and never the
agent itself, which runs from its `-javaagent` jar.

## Name the tests that call your code

Run the agent in your test JVM with `testRun=true` (ADR 0050). See
[`docs/site/test-runs.md`](docs/site/test-runs.md) for the setup.

## Which compilers' output the agent reads

See [`docs/site/compatibility.md`](docs/site/compatibility.md) for the compiler
releases the agent was checked against (ADR 0055) and what it does with code from
any other.

## Design notes

`docs/adr/` records the decisions behind the agent that are hard to reverse
or surprising without context (why probes are a dense per-class array, why
counts are cumulative and merged with `max()`, why some classes are skipped
and reported rather than silently zero). `CONTEXT.md` is the glossary the
agent, the collector, and those records share.

## Status

- Static attach (`-javaagent`) only; no dynamic/runtime attach yet.
- Nothing is published to a package repository yet, the agent jar and
  `testkit` included; both are built from this repo.
- Branch tracking covers two-outcome conditional jumps and
  `TABLESWITCH`/`LOOKUPSWITCH`; `GOTO`/`JSR` aren't tracked (no second
  outcome to observe).
- A class the agent cannot instrument is skipped and reported, not silently
  dropped from coverage. Two causes: a supertype its loader cannot serve as a
  class file, or a failure while rewriting it. See
  [`docs/site/classes.md`](docs/site/classes.md).
- Wire schema (`src/main/proto/otherlode/v1/otherlode.proto`) is published
  to the Buf Schema Registry as `buf.build/otherlode/otherlode` for external
  consumers (e.g. a separately-versioned collector).

## Licence

Apache License 2.0; see `LICENSE`. The agent jar bundles Byte Buddy (with
ASM), protobuf-java, the Kotlin standard library, and the JetBrains
annotations, relocated; their licence texts are under `licenses/` here and
`META-INF/licenses/` in the jar, and `NOTICE` lists them.
