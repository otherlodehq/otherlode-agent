---
status: accepted
---

# A class whose verification or initialiser failed is failed to initialise

Decided on 2026-10-16 with Luke, closing the gap ADR 0066 left. HotSpot verifies a class when it links it, not when it defines it (`InstanceKlass::link_class_impl`, instanceKlass.cpp:794, 876 in jdk21u). A class the verifier rejects stays in the `loaded` state, stays in `getAllLoadedClasses()`, fires no probe and is verified again, and rejected again, on every later attempt (jvmtiEnv.cpp:456-457). A class whose static initialiser throws is erroneous for good: the JVM never runs the initialiser again and every later use throws `NoClassDefFoundError` (instanceKlass.cpp:1108-1127, 1201-1224; JVMS 5.5). Either way the class can never run in that JVM, and either way it read as dead code: the verifier's reject as never initialised or never instantiated with every method never hit, the initialiser's throw as initialised with every other method never hit, since the `<clinit>` entry probe fires before the throw. Every runtime coverage and unused-code tool checked (JaCoCo, Codekvast, Scavenger, IntelliJ's coverage agent, Azul Code Inventory by its docs) reports such a class as unused or drops it without saying so, so there was no precedent to follow, only the gap to avoid.

A class like this is a *class that failed to initialise*: a deployment to fix, never dead code, and apart from a *class that failed to load*, which the JVM never defined. The agent names it only from evidence it is certain of.

- **The initialiser threw.** A `<clinit>` the class file declares gets two slots beside its entry probe. *Returned* counts before each normal return. *Threw* counts in one catch-all handler, last in the exception table so it sees only what leaves the body, which rethrows the same exception. The handler's frame is new and every frame the class arrived with is kept, so the class fails as it does unwoven. Each slot counts once per class. A `<clinit>` the agent adds below class-file version 55 counts nothing, as before.
- **The verifier rejected it.** The agent weaves the constructors of `java.lang.VerifyError` through its bootstrap helper. The woven code only puts the message on a lock-free queue, since it runs under the failing class's initialisation lock and must not block, load or link. The export thread reads the class name from each message by the formats of the HotSpot releases read, 17, 21 and 25, and the old inference verifier's. A message in no read format is an unread shape: the run counts it as an unplaced verification failure and names no class. Who triggered the link does not matter, since a class the verifier rejects in a loader is rejected on every attempt. The match is by name across loaders, as confirmation is, so a wrong match only withholds a claim.
- **The initialiser is still running.** A `<clinit>` that entered and neither returned nor threw has not finished: a class that blocks in its initialiser for good never runs either. The agent sends the counts and names nothing; the server reads them.

The manifest carries `classes_failed_to_initialise`, each entry a class name, a reason (`INITIALISER_THREW` or `VERIFICATION_REJECTED`) and when the agent saw it, added when *threw* first counts above zero or a message names the class. The run carries its count of unplaced verification failures. The *returned* and *threw* slots travel as probe locations of their own kinds, so their counts arrive in deltas like any other.

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
