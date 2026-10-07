---
status: accepted
---

# A method that branch probes would make too large keeps only its entry probe

Decided on 2026-10-04 in the runtime-overhead grill with Luke, settled in review when built on 2026-10-05, and first recorded as an amendment to ADR 0052. This record takes that amendment over.

Probes add bytes, and two limits bound a method's code. HotSpot's `HugeMethodLimit` is 8000 bytes: it never compiles a method over it, so a method the probes push past 8000 runs interpreted for the life of the process. A class file holds at most 65535 bytes of code per method: a method the probes push past it fails the rewrite, and the whole class is skipped and loses every probe for the sake of one method.

`SizeGuard` bounds each method's woven length from above. The bound is the method's length in the class file, plus the entry probe, plus the largest encoding of each construct the weave inserts for its kept branch sites, plus the padding a switch may gain. Above 32767 bytes, where ASM widens any jump whose offset no longer fits 16 bits, it adds five bytes for every jump the woven method could hold, which counts every jump as widened. The bound over-states the woven length and never under-states it. A switch is bounded by one block per case entry not bound for the default, plus the default.

When the bound crosses a limit, the guard drops every kept branch site of the method as `BranchDropReason.SIZE_GUARD` and logs one WARNING naming the method, once the class commits. A method whose sites do not pair (ADR 0052) has no branch probes to lose and gets no WARNING. The method keeps its entry probe. A dropped site is emitted unchanged and keeps its identity and its place in the site numbering (ADR 0025), and no wire field says why. A missing branch finding is a missed finding, never a false one.

The two limits read different bytes:

- **8000** reads the class file alone. So this decision is the same whatever transformer ran ahead of this agent (ADR 0052).
- **65535** reads the larger of the class file's length and the received length, since the rewrite walks the received bytes and an earlier transformer may have grown them. So a method an earlier transformer grew past the limit loses its branch probes, which changes the layout hash, as an earlier transformer that removes a method or breaks a method's pairing does (ADR 0052).

Three cases leave the branch probes alone:

- A method already over 8000 bytes keeps its branch probes while its bound stays within 65535. HotSpot does not compile it either way, so dropping them saves nothing.
- When the entry probe alone can carry a method of 8000 bytes or fewer past 8000, nothing is dropped, because dropping branch probes cannot bring it back under. The method is only named in a WARNING.
- When the entry probe alone overflows 65535, the guard leaves the method alone. The class is then skipped and reported as for any rewrite failure (ADR 0007).

On a re-weave (ADR 0053), `reweaveGuarded` checks the stored plan's kept sites against the received bytes for 65535 only. A method that no longer fits is left out of the branch rewrite. Its planned slots stay registered and are never incremented, so its branch counts freeze and nothing renumbers. One WARNING is logged per class.

## Considered options

- **Weave without a check.** Rejected on a measurement made on 2026-10-04. Branch-dense code of the form `if (x == k) y++` repeated fails at about 17 KB of original code. The failure is clean, with the original bytes defined and the class listed as skipped, but the whole class loses its probes for one method.
- **Drop a method's branch probes whenever the bound crosses 8000.** Built first and rejected in review. A method whose entry probe alone carries it past 8000 stays uncompiled with or without its branch probes, so dropping them lost findings for nothing. Such a method keeps them and is named.
- **A wire field saying a method was dropped for size.** Not added. STATUS recorded the same trade as ADR 0052 made for a method whose sites do not pair: the entry probe is kept, and the method reads as hit or not hit.

## Consequences

- Over the five benchmark corpora the bound is never below the woven length (slack 0 to 88 bytes), and no method is guarded. A test fails if received lengths leak into the 8000 decision.
- The widening allowance counts every jump, so a method over about 45 KB that an earlier transformer grew can lose branch probes it would have kept.
- The guard does not model `jsr` inlining below class-file version 51, or the growth of `<clinit>` and `$default` methods.
- A site dropped at the first weave is counted in the per-class DEBUG line that summarises drops by reason and in the INFO total logged on the first flush that finds any drop. A method guarded on a re-weave is counted in neither; its WARNING is the record.
