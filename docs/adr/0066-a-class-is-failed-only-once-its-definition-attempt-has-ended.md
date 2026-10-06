---
status: accepted
---

# A class is failed only once its definition attempt has ended

Decided on 2026-10-06 with Luke, before the server builds a finding on `failed_classes` (server ADR 0057). Luke's condition was certainty: the agent must never name a class the JVM defined. ADRs 0028 and 0065 inferred failure from absence. A class missing from `getAllLoadedClasses()` on two sweeps was withheld for good and named. Research found three ways a defined class could still be named, each reproduced or traced in source:

- The agent registers a class when its transformer runs. The JVM then still resolves the class's supertypes before the class counts as loaded, and a stall there can outlast two sweeps. Causes include a slow loader, a debugger breakpoint, two sweeps close together at shutdown, and a process frozen or starved of CPU, after which missed sweeps run back to back. A class registered between a sweep's snapshot and its walk also took its first miss for free.
- A definition that failed and then succeeded on a retry in the same loader kept its entry withheld for good, so the name stayed failed.
- A name failed in one loader and loaded later in another stayed failed.

Absence at any number of sweeps only ever shows that a class is not defined yet. So a miss counts only once the definition attempt has ended. At registration the agent records the thread that runs the transformer and every frame from the one that started the definition, such as `ClassLoader.defineClass1`, down to the bottom of the stack. A sweep counts a miss only when that thread has ended, or a read of its stack no longer holds those frames, in order, from its bottom up. The match skips frames the read has and the capture lacks, since JDK 17 shows hidden frames to `Thread.getStackTrace` that `StackWalker` drops. A read cut short, which JDK 21 and later do at `MaxJavaStackTraceDepth` frames, proves nothing and counts as ongoing. Supertype resolution runs inside the definition frame, so a stall, a breakpoint, a freeze or a shutdown keeps the class pending. A class registered after the sweep's snapshot is not judged by it. Each transform of a class that is not confirmed adds an attempt, and a miss counts only once all of them have ended. A transform inside the agent's own transformer call whose attempt cannot be read makes the class unwatchable, so it is never named. A name that registers or confirms again leaves the failed set unless a manifest chunk has carried its failure.

The guarantee is this: when the agent names a class, the attempt to define it in that loader had ended and the class was not defined. No agent can rule out a later retry or a namesake loading later. The server rule that any evidence of loading wins, within one run too, covers that (server ADR 0056).

## Considered options

- **More sweeps, or sweeps spaced by time.** Rejected. A frozen process keeps the monotonic clock running while the loading thread does not, so no spacing proves the attempt ended.
- **`ClassLoader.findLoadedClass` or `Instrumentation.getInitiatedClasses`.** Rejected. They ask about one loader, but the answer is still absence.

## Consequences

- One stack walk per woven class at registration. At a sweep, one stack read for each class still unconfirmed, normally none.
- ADRs 0028 and 0065 named a verifier rejection as a cause. That was wrong. The JVM verifies a class after it defines it, so a class the verifier rejects is defined, is confirmed by name and is never named failed. Its methods can never run, so they read as never hit. `STATUS.md` records that as a follow-up.
