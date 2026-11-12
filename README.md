# Otherlode

A Java agent that instruments a running JVM application to find code that's
reachable but never exercised at runtime: unused endpoints, methods
that are never invoked, and conditionals that only ever take one branch.

Attach it with `-javaagent` and run your app. It sends hit counts to a
collector, and Otherlode reports the probes that never fired.

Customer docs live in [`docs/site/`](docs/site/), synced to otherlode.dev. This
README covers building and developing the agent.

## How it works, briefly

- Method-level probes, written with ASM and woven through Byte Buddy's
  `AgentBuilder`, catch unused methods and classes.
- Endpoint modules for Spring MVC, Spring WebFlux, Javalin, Micronaut 3 to
  5, Ktor, Vert.x Web, JAX-RS, plain servlets and the JDK's `HttpServer` count
  each endpoint where the framework matches a request to it. They also list
  the endpoints the framework registered, so an endpoint that is never called
  still appears.
- Branch-level probes, written with ASM and woven in the same transform pass,
  catch conditionals and switches that only ever take one path.
- The agent batches hit counts and pushes them to a collector on a fixed
  interval, as delta batches plus an incremental probe manifest in the style
  of OTLP. It does not expose them for scraping, so the server behind the
  collector can aggregate results across every instance in a fleet.

## Build

```
./gradlew build
```

The build produces a shaded agent jar at
`build/libs/otherlode-agent-<version>.jar`. It relocates Byte Buddy,
protobuf, the Kotlin standard library and the agent's own wire classes, so
they don't collide with copies already on the target application's classpath.

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

Get the agent jar from Maven Central (`dev.otherlode:otherlode-agent`) or the
GitHub release, and attach it with `-javaagent`, passing `includePackages` at
minimum. See
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

`runDemo` runs a stub collector, an instrumented demo server and a client
that drives the server, each as a separate JVM process. The client always
calls `/checkout` the same way and never calls `/promo`. On shutdown, the stub collector prints a report of
probes that were never hit and any classes it had to skip. `runSpringDemo`
does the same for the Spring Boot fat-jar demo.

A never-hit branch prints as its condition, the result that never happened,
and the lines that run only through that result. Its branch index stays in
brackets at the end. A condition prints as it holds in the code just after the
check. For a plain `if`, that is the condition the source wrote. The demo's
two checkout conditions print like this:

```
NEVER HIT: handleCheckout (DemoServerMain.kt:71) `System.getenv("ENABLE_LEGACY_DISCOUNT") == "true"` was never true, only path to DemoServerMain.kt:72 (instance 17e3afc3-76f1-435c-b30a-5e594350530f, class 0, probe 10) [BRANCH branch#1]
NEVER HIT: handleCheckout (DemoServerMain.kt:78) `discounted > 100.0` was never true, only path to DemoServerMain.kt:79, partly to DemoServerMain.kt:92 (instance 17e3afc3-76f1-435c-b30a-5e594350530f, class 0, probe 12) [BRANCH branch#3]
```

A condition carries the string literals the source compared against, such
as `"ENABLE_LEGACY_DISCOUNT"` above, and so does a string `when` case. The
agent sends them as written. It has no redaction setting of its own. The
collector can replace them before they leave your network (collector ADR
0001), so a deployment that needs redaction sends through a collector with
redaction switched on. An agent that posts straight to a backend sends literals in
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
the part, so the agent marks the forwarder generated and the report never
lists it. Nothing
calls `farewellNote`, so its part never loads and prints by its file:

```
NEVER LOADED: DemoTextFarewell.kt (methods: farewellNote)
```

Some findings are about a whole class, not a method in it. `main` names `AuditLog` and `ReceiptPrinter` through class
literals, which load a class without initialising it. `AuditLog` is an
object, so its static initialiser makes its one instance. That initialiser
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
overload generated, so the report never lists it. Constructors print with their
parameter types:

```
NEVER HIT: com.example.demo.server.Money#constructor(int, int):10 [CONSTRUCTOR, unused overload] (instance 17e3afc3-76f1-435c-b30a-5e594350530f, class 5, probe 1)
```

The last report groups those never-hit probes into unreached clusters. A
cluster holds a root and every never-hit method beneath it whose only callers
are also in the cluster. The root is a method reached from code that does
run, a method never called at all, a branch outcome that never ran in a
method that did, or a class that holds a class finding. `/promo`'s handler calls a helper that calls a
repository method neither the handler nor anything else ever reaches, so it
prints as one four-method cluster: the handler, its helper, and the
repository's method and constructor. The repository class never loads at
all in this demo, and the cluster holds every method of it, so it prints
once as a whole class with its finding. A call into a class counts as a
call into its static initialiser, so the whole class follows the handler
into the cluster. The report never lists or counts the initialiser itself. The handler is a named class, so the endpoint record names its
`handle` method and the report prints the route beside the root. Nothing in the
demo's own code calls `handle`, only the server. The method overrides
`HttpHandler.handle`, and `HttpHandler` is outside scope, so the root reads
as called from outside scope, not uncalled. Code outside scope may call it,
so the root is weaker evidence for deletion:

```
UNREACHED CLUSTER: root com.example.demo.server.PromoHandler#handle (called from outside scope: overrides HttpHandler), 4 methods, 1 never-loaded classes routes=[* /promo]
  com.example.demo.server.PromoRepository (whole class, never loaded, 2 methods)
  applyPromoCode (DemoServerMain.kt)
  com.example.demo.server.PromoHandler#handle
```

The demo registers `/checkout` the other way, as a function reference that
Kotlin converts to the `HttpHandler` interface through `invokedynamic`.
That handler is a hidden class with no stable name. The agent records
which method the handler calls when the JDK generates its class (ADR 0035),
so the endpoint report lists both shapes: `* /promo` names
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
calls it, or when a method that ran does. The report lists it only when
the cluster holds more than the methods the finding folds, since the class
findings report already names the class. A lambda body goes with the
methods that create it: `ReceiptPrinter`'s `print` hands a lambda to
`joinToString`, and kotlinc compiles that lambda to a static method of the
class, which folds into the class finding with `print`. So neither
`AuditLog` nor `ReceiptPrinter` roots a listed cluster.

A never-hit method called from a method that ran, and not from behind an
untaken outcome, is a root "reached from hit", and the report names the
callers after it. The checkout handler also calls its response helper
through a function reference. The class the compiler generates for that
reference is reached from the handler and never appears in a cluster.

## Run the demo against a real collector

These tasks need Otherlode's server, which is not public.

```
./gradlew :demo:runDemoStack
./gradlew :demo:runSpringDemoStack
./gradlew :demo:runShapesStack
```

`runDemoStack` runs the same instrumented demo server and client, but against a
real collector instead of the stub, then prints what the backend behind it
reports. `runSpringDemoStack` does the same for the Spring Boot fat-jar
demo, as service `otherlode-spring-demo`. `runShapesStack` runs three short
programs one after another, each as its own service: `otherlode-shapes`
touches Java anonymous classes and a lambda, a companion object, a data
class and a suspend function, each with a part that never runs, and
`otherlode-fixtures-scala2` and `otherlode-fixtures-scala3` call some of the Scala
fixture modules' `Driver` methods and leave the rest. Each prints the
probe and class counts, the never-hit probes, the never-loaded,
never-initialised and never-instantiated classes, and the unreached
clusters. It expects a collector at `http://localhost:4319` and the
otherlode-server read API at `http://localhost:4320`, which is what
otherlode-server's `docker compose --profile stack up --build` provides. You
can override every address and credential:

| Property | Default |
|---|---|
| `-PotherlodeEndpoint` | `http://localhost:4319` |
| `-PotherlodeAgentToken` | `local-stack-agent-token` |
| `-PotherlodeServerUrl` | `http://localhost:4320` |
| `-PotherlodeServerReadApiKey` | `otl_stack-read-key-for-local-dev-only`, the stack's read key. The demo only reads the server's API. |
| `-PotherlodeServiceVersion` | `stack-demo`, `spring-stack-demo` for the Spring demo, `shapes-stack-demo` for the shapes run |

The defaults match the compose stack's own development defaults, so with
the stack up the tasks run with no arguments. Each run registers as a new
instance, and the server keeps every run until its tenant's retention
period passes. The report therefore covers every run of that service and
version within that period. To report on new runs alone, pass a fresh
`-PotherlodeServiceVersion`.

The demo runs in the unspecified namespace. To run it in a named one, set
`OTHERLODE_SERVICE_NAMESPACE` in the environment of the Gradle command. The
tasks pass it to the demo server, and the report names the namespace.

## Test your app against the agent

The `testkit` module is an embeddable collector for your own tests. See
[`docs/site/testkit.md`](docs/site/testkit.md) for the setup and
[`docs/site/testkit-api.md`](docs/site/testkit-api.md) for every query and wait.

Add `dev.otherlode:otherlode-testkit` from Maven Central to your test
classpath, at the same version as the agent. To build it from a checkout
instead, run `./gradlew :testkit:shadowJar`. It bundles the
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

- Static attach (`-javaagent`) only. The jar has no `agentmain`, so it
  cannot attach to a running JVM.
- A release publishes the agent as `dev.otherlode:otherlode-agent` and the
  testkit as `dev.otherlode:otherlode-testkit` on Maven Central, at one
  version.
- Branch tracking covers two-outcome conditional jumps, `TABLESWITCH` and
  `LOOKUPSWITCH`. The agent does not track `GOTO` or `JSR`, since neither has
  a second outcome to observe.
- The agent skips and reports a class it cannot instrument, rather than
  dropping it from coverage silently. It skips a class for one of two causes:
  a supertype its loader cannot serve as a class file, or a failure while
  rewriting it. See
  [`docs/site/classes.md`](docs/site/classes.md).
- The wire schema (`src/main/proto/otherlode/v1/otherlode.proto`) is
  published to the Buf Schema Registry as `buf.build/otherlode/otherlode` for
  external consumers, such as a collector versioned on its own.

## Licence

Apache License 2.0. See `LICENSE`. The agent jar bundles Byte Buddy (with
ASM), protobuf-java, the Kotlin standard library, and the JetBrains
annotations, relocated. Their licence texts are under `licenses/` here and
`META-INF/licenses/` in the jar, and `NOTICE` lists them.
