---
status: accepted, amends ADR 0027, ADR 0068 and ADR 0082
---

# The final flush waits for the application's shutdown hooks

Decided on 2026-11-02, after an audit of claims against the order evidence arrives in (server ADR 0087).

The agent's shutdown hook ran its final flush beside the application's own hooks. The JDK starts every application hook at once, in no set order, and waits for all of them. A framework's hook, such as Spring's context close running `@PreDestroy` methods or a graceful drain, often runs code seconds later. Its hits reached no delta batch, a class first loaded there reached no manifest, and the closing `final_flush` batch still arrived, so the run read as ended cleanly and settled and the server claimed that code never hit, for good. A test reproduced it with a forked JVM.

## Decision

- **Three steps, one deadline.** The agent's hook starts a deadline 28 seconds out, under Kubernetes' default 30-second grace. It sends a normal flush at once, bounded to 5 seconds, so a process killed later has delivered nearly everything. It then waits for the application's other hooks until 18 seconds in. It then runs the final flush with the time left, at least 10 seconds. Nothing in the three steps waits past its share, and the final flush runs even when the early flush's last send is still going.
- **The agent learns the hooks without opening `java.lang`.** It weaves `java.lang.ApplicationShutdownHooks.runHooks` on entry with inlined advice that reads the class's own `hooks` field and hands the hook threads to a seam class in the bootstrap jar, as `VerifyErrorHook` reaches `java.lang.VerifyError`. `java.base` gets a read edge to the seam; no package is opened to any module. When the weave cannot be installed, the agent sends the early flush and the final flush straight after it, as before, and says so once at startup.
- **A hook the JVM never starts is waited for 1 second at most.** JDK 25 leaves a virtual-thread hook it could not start in state `NEW`, and the JDK's own join of it returns at once.
- **Lines the agent logs once the application's hooks have ended go to standard error,** in the console format of ADR 0081. `java.util.logging`'s own shutdown hook resets every handler, so a line through the application's logging there is lost.
- **The early flush sends no static baseline or class listing retry,** like the final flush.
- **The first forward sweep of ADR 0027 runs on the first flush, and on every flush that walks the loaded classes** once the dependency listing has completed, since that walk is paid anyway. A class loaded with no transformer offered it then reaches a manifest within one flush, the lag every count has.
- **A test JVM** (`testRun=true`) keeps its shutdown as it was.

## Considered options

- **Opening `java.lang` to the agent's module to read the field.** Rejected: the agent jar is on the system class path, so its module is the classpath's unnamed module, and every class there would gain deep reflection into `java.lang`, changing how libraries that probe it behave. Proved with a forked JVM on JDK 17 and 25. ADR 0081 keeps the JVM as the agent found it.
- **A helper in the bootstrap jar with `java.lang` opened to the boot loader's unnamed module.** Rejected: that module is shared with every other agent on the boot class path.
- **`sun.misc.Unsafe` to read the static field.** Rejected: the memory access methods are deprecated for removal and JDK 24 and later print a warning on first use.
- **Not waiting.** Rejected: code a framework runs at shutdown would read never hit for good.

## Consequences

- A shutdown can take up to 28 seconds where it took at most 10, but only when the application's own hooks or the collector are slow. The JVM already waits for every application hook, so a fast shutdown is no slower.
- A hook that runs past 18 seconds has what it runs after that left uncounted, and the agent says so on standard error.
- A hook that calls `Runtime.halt` ends the JVM before the final flush. The early flush has then delivered what ran before shutdown began.

## Settled while building it, 2026-11-02

- **The early flush runs on its own send threads,** and starts no send, chunk or batch after its 5 seconds. A send it stopped waiting for no longer holds a thread the final flush needs, so the final flush's content goes out, not only its closing batch. Up to one early send per side can still be in flight while the final flush runs, so an early manifest pass and the final one can overlap. That amends ADR 0082's rule that two passes never read the registry at once: a pass copies what it sends, so the overlap can send an entry twice, which the server stores once.
- **A flush clears a pending state only when no later flush marked it.** Each flush has a number. Without this, an early send that ended after the final flush began, all its batches confirmed, cleared the pending state a refused final batch had set, and the closing batch said nothing was pending. The hits a hook ran would then read never hit, the false claim this record exists to prevent.
- **The agent writes to standard error from the start of its shutdown hook,** in every run, not once the application's hooks have ended. The JVM starts `java.util.logging`'s hook at the same time as the agent's, so the early flush's lines were lost too, and so was every line when the weave could not be installed.
- **The first forward sweep's lag is one flush only once the dependency listing has completed.** Until then the forward pass runs on the first flush and every tenth after it, so a class loaded with no transformer offered it can take ten flushes to reach a manifest.
