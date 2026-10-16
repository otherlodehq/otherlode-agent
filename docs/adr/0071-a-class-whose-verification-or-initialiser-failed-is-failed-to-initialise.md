---
status: accepted
---

# A class whose verification or initialiser failed is failed to initialise

Decided on 2026-10-16 with Luke, closing the gap ADR 0066 left. HotSpot verifies a class when it links it, not when it defines it (`InstanceKlass::link_class_impl`, instanceKlass.cpp:794, 876 in jdk21u). A class the verifier rejects stays in the `loaded` state, stays in `getAllLoadedClasses()`, fires no probe and is verified again, and rejected again, on every later attempt (jvmtiEnv.cpp:456-457). A class whose static initialiser throws is erroneous for good: the JVM never runs the initialiser again and every later use throws `NoClassDefFoundError` (instanceKlass.cpp:1108-1127, 1201-1224; JVMS 5.5). Either way the class can never run in that JVM, and either way it read as dead code: the verifier's reject as never initialised or never instantiated with every method never hit, the initialiser's throw as initialised with every other method never hit, since the `<clinit>` entry probe fires before the throw. Every runtime coverage and unused-code tool checked (JaCoCo, Codekvast, Scavenger, IntelliJ's coverage agent, Azul Code Inventory by its docs) reports such a class as unused or drops it without saying so, so there was no precedent to follow, only the gap to avoid.

A class like this is a *class that failed to initialise*: a deployment to fix, never dead code, and apart from a *class that failed to load*, which the JVM never defined. The agent names it only from evidence it is certain of.

- **The initialiser threw.** A `<clinit>` the class file declares gets two slots beside its entry probe. *Returned* counts before each normal return. *Threw* counts in one catch-all handler, last in the exception table so it sees only what leaves the body, which rethrows the same exception. The handler's frame is new and every frame the class arrived with is kept, so the class fails as it does unwoven. Each slot counts once per class. A `<clinit>` the agent adds below class-file version 55 counts nothing, as before.
- **The verifier rejected it.** The agent weaves the constructors of `java.lang.VerifyError` through its bootstrap helper. The woven code only puts the message on a lock-free queue, since it runs under the failing class's initialisation lock and must not block, load or link. The export thread reads the class name from each message by the formats of the HotSpot releases read, 17, 21 and 25, and the old inference verifier's. A message in no read format is an unread shape: the run counts it as an unplaced verification failure and names no class. Who triggered the link does not matter, since a class the verifier rejects in a loader is rejected on every attempt. The match is by name across loaders, as confirmation is, so a wrong match only withholds a claim.
- **The initialiser is still running.** A `<clinit>` that entered and neither returned nor threw has not finished: a class that blocks in its initialiser for good never runs either. The agent sends the counts and names nothing; the server reads them.

The manifest carries `classes_failed_to_initialize`, each entry a class name, a reason (`STATIC_INITIALIZER_THREW` or `VERIFIER_REJECTED`) and when the agent saw it, added when *threw* first counts above zero or a message names the class. The run carries its count of unplaced verification failures, `unplaced_verification_failures`. The *returned* and *threw* slots travel as probe locations of the kinds `INITIALIZER_RETURNED` and `INITIALIZER_THREW`, so their counts arrive in deltas like any other.

The server decides, in its ADR 0069: a run in which the class initialised is evidence about its methods whatever another run says; hits always stand; zero counts of a class that failed to initialise in a run say nothing about it from that run; a confirmed subtype of such a class failed to initialise too, from the supertypes `ClassLocation` carries; a run with an unplaced verification failure makes no claim from a zero count; a class whose initialiser is still running gets no class finding from that run; a dependency referenced only from such classes is one to review, not to remove.

## Considered options

- **Leave the gap and document it.** Rejected. A class that failed verification or initialisation was wanted by something and broke, and calling it dead is the false claim the product exists not to make.
- **Name such classes in `failed_classes`.** Rejected. The class was defined and confirmed, so its probes are already published, and the server's rule that any evidence of loading wins (server ADR 0056) would override the name. The two cases also need different words: never defined against never able to run.
- **Detect a failed initialiser by polling `Unsafe.shouldBeInitialized`.** Rejected. It cannot tell a failed initialiser from one still running, and needs an internal export.
- **Read failures from JFR's `jdk.JavaErrorThrow`.** Rejected. It starts the JFR runtime and a disk repository, delivers about a second late, retransforms `Error` on 17 and 21, and an `ExceptionInInitializerError` carries no class name.
- **Tail `-Xlog:class+init` enabled through `VM.log`.** Rejected. It changes the JVM's logging configuration and parses log text with no stable format.
- **Link or initialise a class to test it, by reflection, a method-handle lookup, a retransform or `ensureInitialized`.** Rejected. Each changes when the application's class links or initialises.

## Consequences

- One catch-all handler and two increments in each declared `<clinit>`, run once per class. A class the verifier rejects pays the message hand-off once per attempt, off the probe path.
- `java.lang.VerifyError` is woven at startup. On JDK 17 and 21 JFR also transforms `Error` and `Throwable` when it starts; the two must coexist, and a test pins it.
- A new HotSpot release whose verifier message format differs reads as unplaced until its format is read, which withholds claims for that run's zero counts.
- The wire additions are additive under `buf breaking`. The collector's bindings must be bumped first, since its redaction strips a field it does not know.
- The testkit reports classes that failed to initialise, and its public API only grows.

## Amended on 2026-10-16: what the verifier hook reads

Decided while mapping the hook. HotSpot builds every link-time `VerifyError` through `VerifyError(String)` with a message that names the class, and so does a redefinition or retransformation that fails verification, for a class that keeps running; the instrumentation library then builds a second one with a null message.

- **A null message is ignored**, not counted as unplaced: HotSpot never links with one.
- **A name the agent's own retransformation failed on is dropped.** A class in use that another redefinition failed on has hits or a returned initialiser, which win in the server. What is left, a redefinition with invalid bytes of an unused class with no static initialiser, happens only in development.
- **Every parsed name is sent, in scope or not.** A supertype outside the include rules that fails verification stops its in-scope subtypes from linking, and the server can carry the failure to them only from its name.
- **`ProbeManifest.verifier_rejections_watched`** is true once the hook is installed. While it is false the server makes no claim from the run's zero counts, as for an unplaced failure, and the agent logs one WARNING.
- **The hook keeps up to 8,192 messages between flushes**; each one lost to overflow counts as unplaced.
- **The class-file API's own formats are ignored.** On JDK 25 `ClassFile.verify` builds `VerifyError`s without any class failing; its read formats are recognised and skipped, as HotSpot's are recognised by release. Any other message counts as unplaced.

## Amended on 2026-10-16: a repeated message is kept once

Decided in review of the hook. The JVM verifies a rejected class again on every use and builds the same message each time, so one rejected class on a busy path would fill the ring, count every flush's overflow as unplaced and leave the run with no claim from its zero counts. The hook keeps each message it stored in a table of 64 entries indexed by hash code and skips a message equal to the one its entry holds. A repeat carries no new fact, since the class is rejected on every attempt. Only an equal message is skipped, so a hash collision stores both; a message is entered only once stored, so one the full ring turned away is kept when it comes again. A message stored in a slot sealed before it was read counts as overflow and may be skipped when it comes again: its class goes unnamed, and the run's unplaced count, above zero, withholds every claim from its zero counts.

## Amended on 2026-10-16: the testkit

The testkit reads the facts as server ADR 0069 does, so a test predicts what the server will say. `failedToInitialize()` lists the classes some instance named and no instance initialised, sorted, beside `failedToLoad()`, and `DependencyStatus.FAILED_TO_INITIALIZE` with `failedToInitializeDependencies()` mirrors the failed-to-load ones. Such a class and its confirmed subtypes are left out of never hit, every class finding and the clusters; a class whose initialiser is still running in an instance gets no class finding from it. A zero-count query refuses, as it does for a rejected payload, while any instance is unwatched or has an unplaced verification failure, and names the instance and why: an empty list must never mean the agent could not see. The demo collector reads the facts the same way.

## Amended on 2026-10-16: which subtypes fail with it, and live references

A failure carries to a subtype as the JVM decides (JVMS 5.4, 5.5 step 7). A class the verifier rejected stops every class that extends or implements it, at any depth, from linking. A static initialiser that threw stops its subclasses from initialising, and the classes that implement it only when it is an interface that declares a non-abstract instance method, a default or a private one, which the manifest shows as a non-static method probe other than `<clinit>`. The wire does not tell an interface from a class, so a subinterface is read like an implementing class, which can only withhold a claim. A reference held by a class that failed to initialise is live only through a hit: its class-level reference is not live, and a method of it that ran, such as one the failing initialiser called, is.
