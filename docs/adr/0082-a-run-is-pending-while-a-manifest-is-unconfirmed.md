---
status: accepted, amends ADR 0068
---

# A run is pending while a manifest is unconfirmed

Decided on 2026-11-01, after the soak test's smoke run found a false never-loaded claim (server ADR 0087).

ADR 0068 makes a run's counts pending while a hit batch is unconfirmed, and the server judges a run's zero totals only once its newest payload says nothing is pending. A manifest was left out: "A flush's manifest sends stop at the first failure ... A class whose chunk was refused is absent upstream, not at zero." That holds for its probes, which the server cannot judge without their rows. It does not hold for the class itself. The manifest is how the server learns a class loaded, and a static baseline that declares the class tells the server it exists. So a run whose manifest was refused and whose delta batches were accepted reads as settled, while classes it loaded read as never loaded. A chunk refused for its size is refused on every flush, so the claim would last until the run expires.

## Decision

A run's state is pending while any manifest content it has built is unconfirmed: a manifest chunk a collector refused or that failed to send, and a chunk not sent because the flush's manifest sends stopped at a failure. The state moves as ADR 0068 moves it for hit batches. It becomes the start of the current flush when the first such send is not confirmed, and becomes 0 only when everything outstanding, hit batches and manifest content, has been confirmed. Every rule of ADR 0068 on stamping, ordering and heartbeats is unchanged. Static baseline chunks still carry neither field.

## Considered options

- **The server holds a never-loaded claim back for a run that sent no manifest after its scan.** Rejected: a refusal later in a run leaves earlier manifests accepted, and only the agent knows a chunk was refused.
- **A field of its own for manifest delivery.** Rejected: the server's rule is the same for both gaps, a run whose evidence has not all arrived makes no zero claim, so one state serves both. The field is in the wire already.

## Consequences

- A run with a manifest chunk refused for good, for its size, stays pending, and the server makes no zero claim that rests on it. It shows as counts behind, which names the problem instead of hiding it.
- A collector that refuses manifests and accepts delta batches, such as one with a low element limit, now shows the run as behind.
