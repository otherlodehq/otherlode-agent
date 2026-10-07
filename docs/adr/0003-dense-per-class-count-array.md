---
status: accepted, amended by 0060
---

# Record hits in a dense per-class count array

Each instrumented class gets a synthetic `public static final long[]` field named `$otherlodeProbeCounts`, sized at transform time to the class's method and branch probe count. A probe hit is `probes[index]++` on a slot fixed when the class was woven: no hashing, no map lookup, no synchronization. The increment is deliberately non-atomic. A lost count under contention does not matter for a value exported every 60 seconds, and the export answers "has this ever run, and roughly how often", not an exact tally.

One array type serves both tiers. A count subsumes a boolean (zero means never hit) and adds frequency at no extra cost. "Never hit in six months at ten million requests" is a much stronger signal than a flag.

## Considered options

- A global `ConcurrentHashMap<ProbeId, LongAdder>`. Rejected: a hash and a map lookup on every method entry, for state that is per class and known in full at transform time.
- A separate boolean array for method probes. Rejected: same write cost as a count, less information.

## Consequences

Recording needs no global probe ID space. A flat identifier exists only on the wire (0011). This is the JaCoCo approach.

Amended 2026-10-04 by 0060: a woven class gets the `$otherlodeProbeCounts` field only below class-file version 55, where the probes read it through an accessor (an interface below version 52, which cannot have one, has no probe outside `<clinit>`). From version 55 the probes load the array as a dynamic constant and the class gets no field. The dense array, the non-atomic `probes[index]++` and the choice of counts over booleans stand.
