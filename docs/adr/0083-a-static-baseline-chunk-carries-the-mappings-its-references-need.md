---
status: accepted, amends ADR 0030 and ADR 0036
---

# A static baseline chunk carries the mappings its references need

Decided on 2026-11-02, after an audit of claims against the order evidence arrives in (server ADR 0087).

The server counts a reference toward a dependency only through the run's mapping of the referenced class name to that dependency. ADR 0030 sent every mapping on the manifest and left `StaticBaseline.external_classes` empty, and the baseline recorded its mappings after its backlog was fixed, so they went out on a later manifest. Between a complete baseline arriving and that manifest, a dependency referenced only from code that never loads read `unreferenced`, a lead to delete the jar, where `unreached` is right. A refused or lost manifest chunk stretched that to the run's life. A server test reproduced it for a settled run, one behind, and one with no delta batch yet.

## Decision

- **Each baseline chunk carries the mappings its classes' references need,** in `external_classes`. A mapping goes in the chunk of the first class that references its name, and counts toward that class's size and the chunk's bounds. A class and its mappings are never split.
- **The value is the registry's.** The scan takes each mapping from `ExternalClassRegistry.mappingFor`, the same value the manifest sends for that name, so the two payloads never disagree. A name the transform recorded first keeps the transform's mapping, an absent one included.
- **The manifest still carries the mappings,** held as ADR 0036 holds them. The server keeps the first mapping it stores for a name, so the duplicate changes nothing.
- **A baseline mapping is not held** until its dependency's entry may go out. The server stores a mapping with no foreign key and joins at read time, and skips an id the run never listed, so a mapping that arrives before its entry claims nothing.

The server needs no change: it already stores a chunk's `external_classes` in the chunk's transaction, and a scan counts only once every chunk has arrived, so no read sees a complete scan without its mappings.

## Considered options

- **Holding `dependencies_listed` until the baseline's mappings are confirmed.** Rejected: the agent's only proof is the collector's answer, and the server's judging would rest on a flag rather than on the mappings themselves.
- **A server rule that waits for a manifest after the baseline.** Rejected: baseline chunks carry no sequence number, and the baseline is sent from the scan thread outside the flush, so no later manifest proves it carried the mappings.

## Consequences

- A class near the baseline's element bound gains one element for each distinct name it references, at most 65,535 by the constant pool. A class that fit before can now throw `ClassTooLargeException`, and then no baseline is sent.
- A name the class index does not map, such as one in a jar outside the startup listing, gets a mapping only when the transform records it from a class that loaded. A reference to it from code that never loads still never counts, as ADR 0030 accepts for jars the scan cannot see.
