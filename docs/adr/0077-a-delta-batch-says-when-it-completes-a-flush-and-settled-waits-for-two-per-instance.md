---
status: accepted
---

# A delta batch says when it completes a flush, and settled waits for two per instance

Decided on 2026-10-25 in a grilling session. It amends ADR 0018 and closes the weakness ADR 0036 left in the testkit's settled wait.

The testkit's `awaitSettled` returned once two delta batches had arrived after the call, from any instance. That assumed one batch per flush. A flush reads every probe counter before its first send, and then it can send several batches: a leading heartbeat when counts are pending (ADR 0068), one batch per `maxDeltasPerBatch` deltas or per ADR 0075 bound, rider-only batches for endpoints and dependencies, and a closing heartbeat once pending counts clear. A retry whose response was lost delivers the same batch twice. So one flush that read its counters before the caller's last action could end the wait, and a test then read a method as never hit although it ran. With a child JVM beside the test JVM, the test JVM's heartbeats could end the wait before the child's hits arrived.

## The design

- **A complete flush is marked on the wire.** `DeltaBatch` carries `bool flush_complete = 8`. The agent sets it on the last delta batch of a flush, and only when every earlier delta batch of that flush was confirmed. An idle flush sets it on its one heartbeat. A flush with hits sets it on its last hit or rider batch. A flush that started with pending counts sets it on its closing heartbeat. The shutdown flush sets it on its `final_flush` batch, and only when every hit batch of that flush was confirmed. A flush with a refused or failed batch sets it on no batch.
- **Settled waits for two complete flushes per instance.** `awaitSettled` returns once at least one instance has been heard from, and every instance heard from has either sent two batches marked `flush_complete` since the call began, or ended with a `final_flush` batch that carried `flush_complete`. Flushes run one at a time per instance, and a flush reads its counters only after the previous flush's last send has finished. So the second complete flush after the call read its counters after the call began, and every hit made before the call is in it.
- **A batch counts once.** The testkit counts a batch once per run and `payload_sequence`, so a retried batch does not count twice.
- **A silent instance makes the wait time out.** An instance that went silent, or that ended with a `final_flush` batch without `flush_complete`, never settles. The wait times out, and its message names each instance that has not settled and why.
- **No fallback for an older agent.** The testkit refuses an agent whose version differs from its own, so every agent it hears from sets the field.
- **The collector and the server take the field through their bindings.** The collector's redaction strips a field its bindings do not know and marks the payload `fields_stripped`, so the collector's bindings must name `flush_complete` before an agent that sends it is released. The server stores nothing from it.

## Considered options

- **Count batches per instance and keep the count at two.** Rejected: a split or pending flush still sends two or more batches from one counter read.
- **A flush sequence number on every batch.** Rejected, as in ADRs 0036 and 0068: a failed send moves the rest of a flush into the next one, so a later number proves nothing about the batches before it. The marker states that delivery happened, which only the agent knows.
- **End every flush with its own empty batch.** Rejected: one more request per instance per flush, counted against each tenant's request limit, where the marker rides on a batch the flush sends anyway.
- **A wait based on the flush interval.** Rejected, as in ADR 0036: a wait that passes on a slow run reads missing data as none.
- **Any instance, as before.** Rejected: a test with several instances could read one instance's counts before they arrived.
- **A wait for one named instance.** Not built. It would add a public overload, and the testkit's public API only grows. It can be added later if a test needs it.

## Consequences

- A test that stops a child JVM without its shutdown flush leaves an instance that never settles, so every later `awaitSettled` on that collector times out and names it. The collector the JUnit extension starts accepts one instance only, so this applies to a collector started by hand.
- `awaitSettled` waits for two flushes of every instance. With `flushIntervalSeconds=1` that is about two seconds after the call, as before.
- `awaitNextFlush` keeps its meaning: any delta batch arrived after the call. Its job is to show that the agent is alive.
- The bindings must move in order: the agent's schema reaches the Buf Schema Registry, then the collector's bindings, then the server's. `release.sh` already refuses an agent release whose proto is newer than the collector's and the server's bindings (ADR 0063).
