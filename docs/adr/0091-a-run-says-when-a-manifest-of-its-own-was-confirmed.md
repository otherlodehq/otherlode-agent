---
status: accepted
---

# A run says when a manifest of its own was confirmed

Decided on 2026-11-09 with Luke, after the code review of that day reproduced a false never-hit claim on the server.

A class's manifest is send-once (ADR 0008, amended 2026-10-05). When the collector confirms it, the agent releases the class's probe locations and keeps only each probe's kind, for the delta path. So the agent cannot send a delivered class again.

The backend can lose a run while its process lives. It removes a run that was silent past the tenant's retention period, an operator can prune it or delete its environment or service, and a collector whose namespace setting changes forwards the same run under another service. The run's next payload then makes a fresh run there. Its delta batches still carry counts for every class the process registered before, by class id, and the backend holds no manifest for those ids. A test on the server showed the result: a method the process hit 10 times after the gap was listed never hit, because another run of the service knew the method with zero hits and nothing tied the counts to it.

The backend cannot tell such a run from one that has just started and whose first manifest is still on its way. Only the agent knows that it delivered a manifest before.

## Decision

- **Every payload carries `manifest_confirmed`.** `ResourceAttributes.manifest_confirmed` (field 11) is false until the collector confirms a probe manifest of this run that held at least one class, and true on every payload the run sends after that. It never goes back to false.
- **It is a statement about the agent, not about the backend.** The agent sets it from its own record of confirmed deliveries. A manifest the collector took but whose answer was lost is not confirmed, the agent sends it again, and the field stays false until an answer arrives.
- **The backend reads it when it has no record of the run.** A run it first hears of through a payload that says a manifest was already confirmed has lost what that manifest held. Server ADR 0095 says what the backend does with such a run.

## Considered options

- **A backend tombstone for each run it removes.** Rejected: it covers expiry and prune, and misses the cases the backend cannot see, a namespace change and a move to another tenant's key. It also keeps a record of data an operator asked to delete.
- **The agent sends its manifests again when the backend asks.** Rejected: the agent released them on purpose, to bound its memory (ADR 0008), and the wire has no answer that carries a request.
- **The field on delta batches and manifests only, as `starting` is (ADR 0087).** Rejected: a static baseline chunk or a class listing chunk can be the first payload the backend sees after the gap. It would make the run with the field unset, and the batch that follows would find a run already there.
- **Documentation only**, telling operators to restart agents after such an action. Rejected: the claim is false until they do, and nothing shows that it is.

## Consequences

- A collector must know the field, or its redaction drops it and marks the payload stripped. A stripped run makes no claim, so the gap is safe in that direction.
- `docs/site/data-sent.md` lists the field.
- The dependency listing, the class listing and the static baseline are also sent once. A run that lost only those makes no claim that needs them, since the backend already reads their absence as "not listed". The field does not cover them.
