# Otherlode agent

A Java agent that finds code a running JVM app never exercises. It reports
observations to a collector, which forwards them to the server. The server
decides what is dead.

Read the code for how something works. This file holds rules, not a
description of the design, so it cannot go stale about the code.

## Where to look

- `docs/adr/`: why each decision was made, with the options rejected. Read
  the ADR before changing what it decided. Amendments sit at the end of a
  record, so its opening can describe a design later replaced.
- `STATUS.md`: what is in flight, parked or known to be wrong.
- `CONTEXT.md`: the glossary. Use its terms with its meanings.
- `docs/site/`: the customer docs, synced to otherlode.dev per release. A
  page's file name is its URL, so never rename one. The pages:
  - `attach`, `configuration`: getting the agent running, every option.
  - `methods-and-branches`, `endpoints`, `classes`, `dependencies`,
    `call-graph`: what the agent records and reports.
  - `testkit`, `testkit-api`, `test-runs`: testing against the agent.
  - `compatibility`, `overhead`, `data-sent`, `how-it-works`,
    `troubleshooting`: running it in production.

## Invariants

A change that breaks one of these needs an ADR first.

- **No silent absence.** Anything the agent does not count is reported as
  such. A fatal problem disables the agent entirely: a missing instance is
  visible, a zero-hit one reads as dead code.
- **A woven class loads, fails and reflects as the unwoven class does,
  apart from counting.** Keep received frames; never recompute one.
- **Shape comes from the class file**, not the received bytes, so other
  agents never change a result.
- **The hot path is one array increment.** No lock, map, allocation or
  atomic on a probe hit.
- **Nothing is published before it is confirmed.** The wire has no
  retraction.
- **The agent reports observations.** Thresholds and "dead" belong to the
  server.
- **Inlined code is Java and names no `kotlin.*` type.** The shaded jar
  relocates the Kotlin stdlib; `verifyAgentJar` fails the build on one.
- **Wire changes are additive**, checked by `buf breaking`. A new field
  needs the collector's bindings bumped, since its redaction strips fields
  it does not know.
- **Compiler shapes are recognised by exact body per read release.**
  Anything else is an unread shape, never a guess.
- **The testkit's public API only grows**, pinned by its ABI dump.

## Rules for a change

- **Test first.** Write a failing test that pins the intended behaviour,
  then implement, then refactor. For the instrumentation tiers, whose
  behaviour shows only once bytecode is woven, write an integration test
  that weaves a small fixture class and asserts on its probe hits.
- **Docs follow behaviour, not every code change.** A change that adds
  behaviour a customer can see or set, or modifies behaviour a `docs/site`
  page documents, updates that page in the same commit. A refactor, a fix
  that restores documented behaviour, or an internal change needs none.
- **Releases are deliberate.** Never cut a release or push a `v*` tag
  unless a maintainer asks.

## Comments

- KDoc/Javadoc on public types and functions. An inline `//` comment only
  where the code is genuinely unintuitive: a non-obvious invariant, a
  workaround forced by a constraint, something a reader would misread.
  Don't narrate what the code already says.
- Run the `humanizer` skill over any comment before treating it as
  finished: no em dashes, no stock AI phrasing ("crucial," "seamless,"
  "robust," "leverage"), no sentence that restates the code.
- Never describe code relative to time ("now," "currently," "previously
  did X," "new in this change"). Say what the code does and why; git holds
  when. `STATUS.md` is the exception, since a tracker is about state.
