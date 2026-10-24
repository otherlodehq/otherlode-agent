---
status: accepted, amends ADRs 0014 and 0070
---

# A chunk is bounded by its encoded bytes and its element count

Decided on 2026-10-24 in a grilling session that followed a security audit of the agent.

The chunkers cap a payload by entry count only: 20,000 entries per static baseline chunk, 20,000 names per class listing chunk and 5,000 entries per manifest chunk. A condition part or a line range weighs 1, however long its text. So a few classes with long string constants, or a few hundred very long class names, build a chunk above the collector's fixed 16 MiB body limit. The collector and the server also refuse a payload with more elements than a fixed limit per kind (collector ADR 0008), and the entry weight leaves out some elements that limit counts. A 413 keeps the chunk at the head of its queue (ADR 0014's amendment of 2026-10-07), so one such chunk holds back every chunk behind it for the life of the process.

## The design

- **Three bounds on every chunk.** A chunk of any of the four payload kinds closes when the next entry would take it past its entry cap, past 8 MiB of encoded bytes, or past 90% of the collector's element limit for its kind. The encoded size and the element count come from the same encoding the exporter sends.
- **A class that alone breaks a bound.** A class is never split, so a class can still break a bound on its own.
  - In a static baseline, the scan is dropped for the process, with a WARNING that names the class. A scan without the class would be complete but wrong: the class's call edges and creations would be missing, and another class could read as never constructed.
  - In a manifest, the class goes out as a `SkippedClass` with a fixed reason in place of its probes, so the server knows it loaded and claims nothing about its methods. Its hits are not sent. The chunks behind it go out as usual.
  - A class listing holds names only, and one name is at most 65,535 bytes, so a listing chunk always fits.
- **A 413 still keeps the chunk.** Once every chunk stays under the collector's limits, a 413 comes from a limit in front of the collector, which its operator can raise. ADR 0014's amendment of 2026-10-07 stands.

## Considered options

- **Cut long condition literals to a fixed length.** Rejected: a collector's blocked-value pattern that matches the whole literal may not match a cut prefix, so part of a secret would pass redaction.
- **Leave a class that breaks a bound out of the static baseline.** Rejected for the reason above: the scan would be complete and wrong.
- **Treat a 413 as a refusal of the content.** Rejected again, for ADR 0014's reason: a proxy limit would then cost the scan until a restart.

## Consequences

- A class needs tens of thousands of entries or very long string constants to break a bound. When one does, the run loses evidence and claims nothing false.
- The server's element limits stay the authority. A chunk the agent built under 90% of them fits whatever the server's count leaves out of the agent's weight.
