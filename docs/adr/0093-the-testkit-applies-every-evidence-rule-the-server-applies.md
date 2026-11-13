---
status: accepted, amends ADR 0056 and ADR 0071
---

# The testkit applies every evidence rule the server applies

Decided on 2026-11-13 with Luke, before the first release, after the 2026-11-12 parity audit compared each testkit query with the server's judgement.

ADR 0056 made the testkit a local preview of what the server will say. The audit found the server true to its own ADRs everywhere, and the testkit short of them in about twenty places. In most of them a test sees a dead-code claim the server withholds, which is the worst way for the two to differ.

## Decision

The testkit reads the facts the way the server reads them, rule for rule:

- **A class any run skipped supports no claim that rests on missing hits** (server ADR 0079): no never-hit row, class finding, supplied finding or cluster root or member.
- **A module any run disabled withholds its endpoints, and a module that failed before it declared anything withholds every endpoint** (server ADR 0090). A route found only at dispatch is never called "never called" (server ADR 0067).
- **A class whose initialiser failed or is still running is evidence for nothing** (server ADR 0070), in every zero-count query: always supplied, never called, the dependency statuses and the clusters. Its untaken outcomes and zero-hit generated methods block their callees. The hit methods of a class that failed to initialise are not hit callers either, since the server keeps such a class out of its graph, though its hit generated methods are. A class whose initialiser is still running keeps its hit callers. `neverCalled` decides it per instance that reports the endpoint, as the server decides it per run.
- **A declared method of a skipped or unreported class is an unknown caller** that blocks its callee.
- **Dependency reference liveness spans instances** and counts a loaded class that has no probes, and references come only from complete scans.
- **Every payload passes the same checks.** A class listing goes through the stripped, version and run checks, and a static baseline chunk must carry a valid index and the count of its scan.
- **`neverLoaded()` judges a class when any complete scan declares a judgeable method of it**, and leaves out the scan of a run that stopped while starting (server ADR 0092).
- **A run that is still starting is not settled.** `awaitSettled()` also waits until no live instance's payloads say `starting`, and a run with no delivered delta batch is not evidence. This follows server ADRs 0087 and 0092.
- **Baselines union.** For each class, the testkit takes the union of each instance's latest complete scan, as the server does, so the answer does not depend on which arrived first.
- **The server's orders and representatives.** The outside caller comes from the newest copy that has one, a lambda folds only into its own class's finding, and results sort as the server sorts them.

The testkit does not model removal (server ADRs 0066 and 0091). It needs two builds reporting to one test collector one after the other, which a test session does not do. `testkit-api.md` says so.

## Considered options

- **Refuse zero-count queries while an instance is starting**, in place of waiting. Rejected: a test that starts its JVM and asks at once would fail where the server would simply answer later, and `awaitSettled()` already exists to wait.
- **Keep the testkit's own rules and document each difference.** Rejected: the testkit's value is that it predicts the server. A documented difference is still a wrong answer in a test.
- **Model removal.** Rejected: the testkit would read and act on class listings for a case a test session never produces.
- **Keep the first complete baseline per class.** Rejected: the answer would depend on arrival order.

## Consequences

- A test can wait up to ten flush intervals longer after its JVM starts, until the agent stops marking payloads `starting`.
- Several documented testkit behaviours change, among them that a hit method of a class that failed to initialise roots a `REACHED_FROM_HIT` cluster. `testkit-api.md` follows the code.
- A new server evidence rule needs the same rule in the testkit, or this ADR is broken.
