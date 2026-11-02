---
status: accepted, amends ADR 0068, amended by ADR 0084, ADR 0086 and ADR 0087
---

# A run is pending while a manifest is unconfirmed

Decided on 2026-11-01, after the soak test's smoke run found a false never-loaded claim (server ADR 0087).

ADR 0068 makes a run's counts pending while a hit batch is unconfirmed, and the server judges a run's zero totals only once its newest payload says nothing is pending. A manifest was left out: "A flush's manifest sends stop at the first failure ... A class whose chunk was refused is absent upstream, not at zero." That holds for its probes, which the server cannot judge without their rows. It does not hold for the class itself. The manifest is how the server learns a class loaded, and a static baseline that declares the class tells the server it exists. So a run whose manifest was refused and whose delta batches were accepted reads as settled, while classes it loaded read as never loaded. A chunk refused for its size is refused on every flush, so the claim would last until the run expires.

## Decision

A run's state is pending while any manifest content it has built is unconfirmed: a manifest chunk a collector refused or that failed to send, and a chunk not sent because the flush's manifest sends stopped at a failure. The state moves as ADR 0068 moves it for hit batches. It becomes the start of the current flush when the first such send is not confirmed, and becomes 0 only when everything outstanding, hit batches and manifest content, has been confirmed. Static baseline chunks still carry neither field. ADR 0068's rules on stamping and ordering are unchanged; its heartbeats change as below.

## Considered options

- **The server holds a never-loaded claim back for a run that sent no manifest after its scan.** Rejected: a refusal later in a run leaves earlier manifests accepted, and only the agent knows a chunk was refused.
- **A field of its own for manifest delivery.** Rejected: the server's rule is the same for both gaps, a run whose evidence has not all arrived makes no zero claim, so one state serves both. The field is in the wire already.

## Consequences

- A run with a manifest chunk refused for good, for its size, stays pending, and the server makes no zero claim that rests on it. It shows as counts behind, which names the problem instead of hiding it.
- A collector that refuses manifests and accepts delta batches, such as one with a low element limit, now shows the run as behind.

## Settled while building it, 2026-11-02

- **A late heartbeat says what the flush's stamps missed.** A flush's manifest and delta sends run side by side, so a hit batch can be stamped 0 before a manifest send fails. After both sends end, a flush sends one more heartbeat when manifest content became pending after the newest stamp, or when the state became clean while the newest stamp said pending. Without it a refused first manifest left the run reading settled for one flush interval, which is the window server ADR 0087 exists to close. Hits that become pending after their own flush's stamps still wait for the next flush's leading heartbeat, as ADR 0068 has it. The late heartbeat is not sent when a delta batch or heartbeat of the flush failed without a refusal, with a status or with none: the collector, or Otherlode behind it, is not taking payloads, so an outage costs no extra send. A manifest failure, of any kind, after the delta side was accepted still gets it. When it fails without a refusal the flush counts as unconfirmed. It carries no `flushComplete`, so it can follow the batch that does.
- **The shutdown flush closes after its other sends, within its budget.** Its `finalFlush` batch goes last and carries the state the others left: the two main sends, the release manifest, and the unplaced-count and `dependenciesListed` manifests. The flush waits for each only until 2 seconds before the shutdown budget ends; the budget also covers waiting for a flush already in flight, so with less than 2 seconds left it does not wait at all. A send it stops waiting for marks what it carried as pending, and a delta send stopped that way also keeps `flushComplete` off the closing batch. The release manifest and the late manifests start only while more than twice the reserve is left; skipped, an unplaced count still outstanding marks the run pending. So one slow send no longer costs the run its closing batch, and the closing batch never says nothing is pending while content is in flight. A closing batch that itself takes longer than the reserve can still miss the budget, and the run then reads as one that went silent. A send it stopped waiting for can still finish after the closing batch and stamp a higher sequence; the run then reads pending, which withholds its evidence and invents none. The release manifest is not sent when the first manifest pass timed out, so two passes never read the registry at once.
- **Every manifest that carries evidence counts,** the empty one that carries only the unplaced verification failure count included. A refused one marks the run pending, and a manifest pass clears the pending state only when no unplaced count is outstanding. The empty manifest that carries only `dependenciesListed` does not: without it the server only withholds dependency claims.
- **A flush reads its starting state once,** before its sends start, so whether it leads with a heartbeat no longer depends on which send thread runs first.
