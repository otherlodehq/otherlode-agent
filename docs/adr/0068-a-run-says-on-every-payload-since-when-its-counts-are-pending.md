---
status: accepted
---

# A run says on every payload since when its counts are pending

Decided on 2026-10-07 with Luke, in the grill on the customer docs round's follow-ups.

A flush sends its manifest and its delta batches side by side, and a collector, or a proxy in front of it, can refuse one and accept the other. ADR 0009 offers an unconfirmed delta again on the next flush, recomputed from the current totals against the same confirmed baseline, so no hit is lost; but until one is confirmed the hits it carries are missing upstream. A run whose manifest arrived and whose deltas did not then reads as a full inventory at zero hits: a false "never hit" for every probe no other instance covers. A batch with hits to carry is never empty, and below 20000 entries a flush sends one batch, with endpoint and dependency deltas riding on it. So once that batch is refused, no delta batch arrives at all, and nothing tells the collector the run is behind.

The hits a run has sent in a delta batch that no collector confirmed are its *pending counts*. A delta batch that carries at least one delta, of a probe, an endpoint or a dependency, is a *hit batch*; a heartbeat carries none, so its outcome never makes or clears pending counts. Every `DeltaBatch` and every `ProbeManifest` carries two fields. Static baseline chunks carry neither.

- `payload_sequence`: a number the run gives each delta batch and manifest, from 1, one higher each time.
- `counts_pending_since`, in milliseconds since the epoch: the run's pending state when the payload is stamped. It is 0 when the run has no pending counts, and otherwise the start of the flush in which they began.

The state moves at two points. When a hit batch is sent and not confirmed, and the state is 0, it becomes the start of the current flush at once, so a batch of the same flush stamped after the refusal carries it. When a flush's delta sends end, and the flush sent every hit batch it built and the collector confirmed each one, the state becomes 0. A flush whose delta sends ended before every hit batch was sent, because a transport failure or an exhausted retry ended them, clears nothing, even when it confirmed every batch it did send.

A payload is stamped with both fields just before it is sent, after it is built: the number is drawn and the state read together, under the lock that every change of the state takes. A flush's manifest and delta sends run on two threads, so this is what makes "the highest number wins" right. Any payload numbered after a change of the state read the state after it.

A flush that starts with pending counts sends an empty delta batch, the heartbeat, first and on its own, so the field reaches the collector even when the hit batch is refused for its size. A flush that clears its run's pending counts ends with another heartbeat, stamped after the state became 0, so the collector learns at once that the run caught up. Neither heartbeat carries counts, so neither takes part in the gates below. The shutdown flush follows both rules, within its budget: a closing heartbeat the budget does not reach leaves the run's last word reading as pending, which withholds evidence and never invents it.

A delta batch counts as refused when the collector answers it with a 4xx status the exporter does not retry (ADR 0009). The exporter's failure carries that as a flag of its own, since a 408 or 429 that used up its attempts fails with the same status-bearing exception. A refused batch does not stop the flush: the batches after it are still sent, so a refusal of one never holds back the others. A transport failure, a status the exporter retried until its attempts ran out, or any other answer ends the flush's delta sends, so one flush's worst case stays close to a single send's and a slow collector cannot spend the shutdown budget on one batch after another. Dependencies become sendable only once every delta batch of a flush is confirmed (ADR 0036), and the static baseline's retry and `dependencies_listed` wait for a flush whose every send was confirmed (ADRs 0014 and 0036).

A flush's manifest sends stop at the first failure, which keeps their worst case at one send too. A class whose chunk was refused is absent upstream, not at zero: the server holds deltas for probes it has no manifest row for until the row arrives. A disabled endpoint module is different. Its record is the only thing that tells a collector the endpoints earlier manifests declared are no longer counted, so an undelivered one is not left to ride on a class chunk with the endpoints: it goes first, on a manifest of its own, before any class chunk is built, and never also rides an endpoint chunk.

A run's zero hits are evidence of "never hit" only once a delta batch from it has been received, and only while the payload with its highest `payload_sequence` carries `counts_pending_since` 0. A payload that arrives late, after a send the agent gave up on, is ordered by its sequence and not by its arrival. The first condition covers a run whose every delta batch was refused from its first flush and whose only accepted payloads came from that flush, which carry 0. That rule belongs to whoever judges findings (ADR 0015); the server records how it applies it and how it shows a run whose counts are behind.

ADR 0036 rejected a flush sequence number as the way to know a dependency's counts were delivered, since a failed send moves the rest of a flush into the next one and only the agent knows which sends were confirmed. Here the number proves nothing about completeness: it only orders a run's payloads, so the newest one's `counts_pending_since` wins.

## Considered options

- **The server's rule alone: no zero-hit evidence before a run's first delta batch.** Covers a run refused from the start. Misses one refused after a while, whose earlier batches were accepted.
- **Withholding manifests until the flush's delta batches are confirmed.** A probe first hit after the refusals began still reads as never hit, from a manifest that went out earlier.
- **Sending the manifest after the delta batches, so it carries its own flush's outcome.** Closes the gap in the first consequence below for the manifest alone, at the cost of the two sends running in turn. Rejected: the next flush's heartbeat carries the same news, and the gap it leaves is one the server already reads as an instance gone silent.
- **A flush sequence shared by every payload of a flush.** Rejected: the closing heartbeat belongs to the same flush as the batches before it and says something newer, so a flush number cannot order the two.
- **Carrying the state on `ResourceAttributes`.** Rejected: the resource is what a process is, the same on every payload, and this is the state of one run's delivery.
- **Ordering payloads by arrival.** Rejected: a send the agent gave up on can still be applied after a later one.
- **Stopping a flush's delta sends at the first refused batch.** Rejected: once the field says the run is behind, it holds back the other batches for nothing.
- **Sending past every failure, transport failures included.** Rejected: during an outage each batch would run the exporter's full retry budget in turn.

## Consequences

- One gap remains. In the flush where refusals begin, the payloads stamped before the first refusal carry 0. If nothing the run stamped after the first refusal is accepted, up to one flush interval of hits is missing and the run reads as one that went silent without a final flush, the same reading and the same loss as an instance that died then.
- A flush that starts with or clears pending counts sends one more request, and a flush with a refused hit batch sends every batch it has.
- The fields are additive (`buf breaking`). The collector's bindings are bumped with them, since redaction strips a field it does not know.
- The testkit's collector confirms every payload it can apply, so it reads the fields but exposes neither.

Amended 2026-10-07 while building it: a heartbeat whose send fails without a refusal ends the flush's delta sends like any other failure, the closing one included, and the flush then counts as unconfirmed, so it sends neither `dependencies_listed` nor a static baseline retry into what looks like an outage. By then the closing heartbeat's flush has already recorded its counts as delivered (ADR 0036) and cleared the state; the next flush's payloads carry the 0 the heartbeat would have. A refused heartbeat still counts against nothing.
