---
status: accepted, amends ADR 0082 and ADR 0086
---

# A run says it is starting in a field of its own

Decided on 2026-11-02 with Luke, after the soak test's third smoke run.

ADR 0086 keeps a run from reading settled before its application has loaded its classes. It did so by stamping every payload of a starting run with `counts_pending_since`. That field already meant something else: hits or manifest content the run sent and no collector confirmed (ADR 0068, ADR 0082). A run whose newest payload says its counts are pending is never settled, even after it dies, since the hits it never delivered could be the only ones (server ADR 0059). So a run killed while starting read as unsettled until it expired. In the smoke, a crash-cycle pod sent one flush seven seconds after it started and was killed. For the 30-day retention period, its environment made no never-hit, never-called or unreached claim about anything it knew. A crash loop, an out-of-memory kill at startup or a fast scale-down would do the same in production.

The two meanings call for different readings once the run is gone. Undelivered hits stay a reason to withhold after the run dies. A run that was only starting has delivered everything it counted up to its last flush, like any run that dies between flushes.

## Decision

- **Payloads carry `starting`.** `DeltaBatch.starting` (field 9) and `ProbeManifest.starting` (field 24) are true on every payload the run sends while it is starting, by ADR 0086's rule, and false after. It never goes back to true.
- **`counts_pending_since` keeps its own meaning.** It is no longer set for the starting phase. It is set only when hits or manifest content are unconfirmed (ADR 0068, ADR 0082).
- **The late heartbeat covers both.** A flush sends one more empty batch when the run stops starting, or its pending state changes, after the flush's payloads were stamped.
- **Everything else in ADR 0086 stands:** when starting ends, the cap, the monotonic clock, and the final flush's rule.

## Considered options

- **Server only: a dead run whose newest payload said pending is no evidence.** Rejected: a run that died with hits it never delivered would then stop withholding, and those hits could be the only ones, which ADR 0059 forbids.
- **Keep ADR 0086 as it was.** Rejected: a month without zero-count claims after any pod dies during startup.

## Consequences

- Server ADR 0092 reads `starting`. A starting run holds claims back while it is live and holds nothing once it is not.
- A collector must know both fields, or its redaction drops them and marks the payload stripped.

## Settled while building it, 2026-11-02

- **The late heartbeat also says hits refused after the flush's stamps.** ADR 0082 left hits that became pending after their own flush's stamps to the next flush's leading heartbeat. Before this record a starting run's stamps always said pending, so that gap could not show for one. Now they can say 0, and a starting run that died right after such a refusal would settle with its lost hits read as zeros. So a flush sends the extra empty batch whenever the newest payload a collector accepted no longer says the run's state, unless a delta batch or heartbeat of the flush failed without a refusal. It compares with the newest accepted payload, not the newest stamped one: two hit batches refused in one flush leave the second stamped pending while the server still holds a 0. That closes the same gap for a settled run. While a collector refuses everything, each flush sends one more refused request.
