---
status: accepted
---

# A type missing from the classpath is described as a placeholder while weaving

Decided on 2026-10-04 in a grilling session with Luke, with 0058.

With type validation off, 51 classes in the overhead ceiling run (PetClinic REST, `includePackages=org.springframework`) still failed: each names an optional dependency absent from the classpath (Reactor, Querydsl, `kotlin.reflect`) in a field's or method's signature, and ByteBuddy resolves those types while it builds the instrumented type (`InstrumentedType.java:465`, `:467`) or computes frames (`AsmClassWriter.java:611`), where the JVM resolves them only on use. 35 of the 51 load and initialise without the agent.

The pool the agent hands `AgentBuilder` describes a type it cannot locate as a placeholder: a public top-level class extending `Object`, with as many type variables as the class files it has parsed give the name type arguments, since ByteBuddy's lazy parameterized types require the counts to agree. The hook wraps the pool's cache provider, which is where an unresolved result would otherwise be cached and returned. No frame is computed from it (0061): the woven class keeps the frames of the bytes it received.

A class whose own superclass or interface is missing is still refused: the JVM cannot define it with or without the agent, and weaving it would publish probes for a class that never defines. The guard walks the supertype names, since the pool wraps placeholders in lazy descriptions.

Only the `AgentBuilder` pool uses placeholders. The agent's own class-file reads, the static scanner and reference resolution keep strict pools, where "resolved" still means present.

A prototype on 2026-10-04 wove all 51, and wove 1,772 classes that name no missing type to the same bytes as without placeholders; the amendment below records what the first version of this decision got wrong.

## Considered options

- **Parking the gap.** Rejected: the classes are reported as skipped, so nothing reads as dead, but an adopter class with an optional integration in a signature loses its coverage, and Luke's rule is to skip only what cannot be woven.

## Consequences

- The hook relies on ByteBuddy internals (`TypePool.Default`'s cache provider and `doParse`). A test weaves every corpus class with and without placeholders and requires identical bytes for every class that wove without them, so an upgrade that breaks the hook fails the build.
- A woven class must behave as its unwoven twin does, failing with the same error where it fails; the amendment below says how the first version of this decision broke that and what holds it.
- Counting type arguments parses the classes a transform read a second time, skipping code, only once a placeholder is asked for its type variables; a transform that meets no missing type pays nothing for it.

Amended 2026-10-05, Luke's choice after review: a placeholder may describe, never guess. The first version let ASM recompute frames over placeholders, and three ways that changed what the application sees were reproduced: a class that fails without the agent for want of a type (`PropagationContextElement$ReactorDelegate`, missing Reactor) defined and initialised woven, so a framework probing an optional integration by catching `NoClassDefFoundError` would think it present; a class that fails both ways failed woven with a `VerifyError`, which escapes that catch; and, through a loader that defines classes without serving their class files, a class that verified and ran unwoven failed woven with `VerifyError: Bad return type`, a merge of its types widened to `Object`. Guards against those (refuse a merge naming a placeholder, refuse a class whose own frames name one, keep the pool strict for a loader that serves no class files) were built and reviewed, and had a hole: a merge walks supertypes the guard never saw. They were dropped for 0061, under which no frame is recomputed: the woven class's frames are the class file's, so a placeholder never enters one, and each of those three classes behaves as its unwoven twin.

So the pool describes only. A class any of whose supertypes, transitively, is missing or unreadable from its loader is still refused (it cannot be defined either way), with one WARNING and the reason in the manifest's skipped list. ByteBuddy rewrote parts of the class header from the description: the class-level generic signature (an interface bound on a missing type became a class bound) and the InnerClasses entries (a member type described as a placeholder lost its outer class, so reflection on it threw `IncompatibleClassChangeError`; a local class's own entry changed its simple name, which predates this decision). The woven class keeps the header of the bytes it received on every path, and the method tier's ByteBuddy adds no visibility bridge, which it did by default, giving a public class an extra synthetic method. The first-flush INFO line counts first weaves that described a type missing from the classpath, annotation types aside. The verifier test (0058) requires a woven class to run where its twin runs and to fail with its twin's error where its twin fails, with no exception by name, and every woven class's signatures to equal the received bytes'.
