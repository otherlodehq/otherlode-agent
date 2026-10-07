---
status: accepted, amended by 0058 and 0060
---

# Instrument with ByteBuddy: Advice for methods, raw ASM for branches

Otherlode instruments classes through a single ByteBuddy `AgentBuilder`, the same shape as OpenTelemetry's Java agent. Method probes are ByteBuddy `Advice`, which inlines to bytecode with no reflection or dispatch on the hot path. Branch probes need code inserted at jump targets inside a method body, which `Advice` cannot express, so that tier is a raw ASM `MethodVisitor` hosted in the same transform through `AsmVisitorWrapper`. One agent, one transform pass per class.

## Considered options

- Raw ASM throughout. Rejected: ByteBuddy provides declarative type and method matching and the whole `premain` wiring. Hand-written visitors are kept for the one thing `Advice` cannot do.
- Forking or embedding JaCoCo. Rejected: JaCoCo's probe insertion is the technique Otherlode borrows for branches, but its runtime, agent, and report model are built for test coverage, not for a long-running export to a collector.

## Consequences

ByteBuddy runs with its default REBASE type strategy rather than DECORATE, because the design adds a field to each class (see 0003). That rules out some classes ByteBuddy cannot redefine; 0007 covers how those are handled.

Amended 2026-10-04 by 0060: from class-file version 55 a woven class gets no field. Below 55 it keeps the field, written in ASM by `ProbeArrayMembers` (0060's amendment).

Amended 2026-10-05 by 0058's amendment, "the method tier decorates": the method tier decorates rather than rebases, through `DecoratingTypeStrategy`, so no added field forces a rebase. The entry and omission probes are written by the agent's own ASM, not `Advice`, so the method tier uses ByteBuddy for matching, the `premain` wiring and the transform pass, and ASM for every probe it inserts.

Corrected 2026-10-07: the opening's single `AgentBuilder` and one transform pass per class hold for the method and branch tiers only. The endpoint tier (ADR 0017) and the lambda-factory hook (ADR 0035) are pipelines of their own, each on stock `DECORATE`, since their advice only extends method bodies.
