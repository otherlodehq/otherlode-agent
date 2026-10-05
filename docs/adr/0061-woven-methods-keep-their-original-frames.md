---
status: accepted
---

# Woven methods keep their original frames

Decided on 2026-10-05 in a grilling session with Luke, who took the main session's recommendations for the questions settled after the research. Amends 0006, 0052 and 0058.

The branch tier made ASM recompute every stack map frame of a class it rewrote (`COMPUTE_FRAMES`). A recomputed frame can differ from the class file's in what the verifier must load: spring-core's `PropagationContextElement$ReactorDelegate` types a local `ContextView`, so without Reactor it fails verification with `NoClassDefFoundError`, while ASM types the local `Context`, the check goes, and the woven class defined and initialised. A framework probing an optional integration by catching `NoClassDefFoundError` would read it as present. And a frame merge asks the type pool for a common superclass, which a type the pool cannot see widens to `Object`.

So every frame at an original instruction stays exactly as the bytes being rewritten have it, and the agent adds a frame only at a label it inserts. The probes push and pop the operand stack and add no local (0060 reloads the array at each probe), so each inserted label, which has one predecessor, the jump or switch it sits on, takes the state at that instruction with the jump's operands popped. ASM's `AnalyzerAdapter` tracks that state from the expanded frames, as JaCoCo's instrumenter does. The verifier then checks at each inserted label exactly what it checked at the original jump. Where an inserted fall-through label lands on an instruction that carries a frame of its own, the class file's frame wins, since it covers every predecessor; keeping the inserted one instead gave `VerifyError: Inconsistent stackmap frames` in the research. Max stack grows by six, the probes' peak.

The agent never computes a frame: the class writer's `getCommonSuperClass` throws, so any path that would recompute one fails loudly instead of guessing. Where the agent cannot derive a frame for a label it inserts, the transform fails and the class is skipped and logged, never woven with recomputed frames; a class that passes the type checker has a frame after every unconditional jump, dead code included, so this happens only for one that would not verify, or one at version 50 leaning on the JVM's fallback to type inference. A class at version 50 or above that carries frames keeps them as above; the branch tier writes no frame in one without (below 50, including after the subroutine inliner, or at 50 without a StackMapTable), and the JVM verifies it by type inference as it does unwoven. Frames the agent does not write still appear: ByteBuddy's Advice writes its own at 50, and the probe-array accessor (0060) writes one at every version below 55, which ASM writes as a `StackMap` attribute below 50 that HotSpot ignores. Bytes an earlier agent rewrote keep the frames that agent wrote.

## Considered options

- **Keep `COMPUTE_FRAMES` and guard against its guesses**, as the first form of 0059's amendment did: refuse a merge that names a missing type, refuse a class whose own frames name one. Rejected after review: a merge walks supertypes the guard never sees, so a missing supertype still became a guess, and the guards caught the `ReactorDelegate` shape only when a generic signature happened to force a substitution, never for a class whose missing type appears only in its code.
- **Fall back to recomputing frames for a class whose frame cannot be derived.** Rejected: it brings the risk back for exactly the unusual classes most likely to need it. A skipped class is honest, and its WARNING shows how often it happens.

## Consequences

- A woven class verifies against the same frames as its class file, so it loads, and fails, exactly what the unwoven class does. `WovenClassVerificationTest` (0058) checks both directions: a woven class that fails where its twin runs, or runs where its twin fails, is a violation.
- With 0059's placeholder pool, two spring-context classes that recomputing frames could not weave (`AbstractRetryInterceptor$ReactorDelegate`, `MethodValidationInterceptor$ReactorValidationHelper`, a merge needing Reactor's `Flux`) weave and behave as their twins, as did spring-core's `PropagationContextElement$ReactorDelegate` in the research; without the pool they name a missing type and are skipped.
- With frames never computed, 0059's frame guards are redundant: the placeholder pool lands with its supertype guard, its signature restore and its counting, and without a frame-merge hook, a frame-type guard or a per-class check of the loader.
- Weave time is unchanged within noise; the writer's type-pool lookups go, a few percent of the class-file reads the startup investigation counted, not a startup win to advertise.

## Amendment, 2026-10-05: the rebase's frames are gone

Under the decoration of ADR 0058's amendment no frame comes from ByteBuddy's rebase: the appended
`<clinit>` scaffold, with its two full frames, is not written, and the below-55 accessor's single
frame is the agent's own, from version 50. `Advice` still writes the frames of its entry and
omission probes until those move into the agent's ASM in the next chunk of the round.
