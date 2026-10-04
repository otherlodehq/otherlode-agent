---
status: accepted
---

# A type missing from the classpath is described as a placeholder while weaving

Decided on 2026-10-04 in a grilling session with Luke, with 0058.

With type validation off, 51 classes in the overhead ceiling run (PetClinic REST, `includePackages=org.springframework`) still failed: each names an optional dependency absent from the classpath (Reactor, Querydsl, `kotlin.reflect`) in a field's or method's signature, and ByteBuddy resolves those types while it builds the instrumented type (`InstrumentedType.java:465`, `:467`) or computes frames (`AsmClassWriter.java:611`), where the JVM resolves them only on use. 35 of the 51 load and initialise without the agent.

The pool the agent hands `AgentBuilder` describes a type it cannot locate as a placeholder: a public top-level class extending `Object`, with as many type variables as the class files it has parsed give the name type arguments, since ByteBuddy's lazy parameterized types require the counts to agree. The hook wraps the pool's cache provider, which is where an unresolved result would otherwise be cached and returned. Frame computation asks the same pool, so a merge involving a missing type comes out as `Object`, which the verifier accepts wherever the original frame did not itself need the missing type.

A class whose own superclass or interface is missing is still refused: the JVM cannot define it with or without the agent, and weaving it would publish probes for a class that never defines. The guard walks the supertype names, since the pool wraps placeholders in lazy descriptions.

Only the `AgentBuilder` pool uses placeholders. The agent's own class-file reads, the static scanner and reference resolution keep strict pools, where "resolved" still means present.

A prototype on 2026-10-04 wove all 51, each defining and initialising exactly as its unwoven twin (35 initialised, 16 failed for the missing class both ways), and wove 1,772 ordinary classes to bytes identical with and without placeholders.

## Considered options

- **Parking the gap.** Rejected: the classes are reported as skipped, so nothing reads as dead, but an adopter class with an optional integration in a signature loses its coverage, and Luke's rule is to skip only what cannot be woven.

## Consequences

- The hook relies on ByteBuddy internals (`TypePool.Default`'s cache provider and `doParse`). A test weaves every corpus class with and without placeholders and requires identical bytes for every class that wove without them, so an upgrade that breaks the hook fails the build.
- A class that fails either way may fail with a different error woven: one spring-webmvc class fails unwoven with `NoClassDefFoundError` and woven with a `VerifyError`, since recomputed frames lead the verifier to a different missing type. 0058's verifier test requires only that a woven class fail where its twin fails.
- Counting type arguments is a second parse of each class the pool reads, skipping code; its cost goes into the overhead measurements.
