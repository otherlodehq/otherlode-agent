---
status: accepted
---

# The agent and the testkit share a version; the collector has its own

Decided on 2026-10-05 in a grilling session with Luke, as `STATUS.md`'s pre-release checklist item 6, after research into what actually couples the three. This supersedes ADR 0057, whose namespace, artifacts, `0.x` start and in-tree version stand and are restated here.

Two artifacts go to Maven Central under `dev.otherlode`, verified by a TXT record on the apex `otherlode.dev`: `otherlode-agent`, the shaded `-javaagent` jar, and `otherlode-testkit`. Wire, bootstrap and the endpoint modules ride inside the agent jar and are not published. The agent and the testkit share one version and are released together from one build, since the testkit refuses a payload whose `agent_version` is not its own (ADR 0056) and its shaded codec rejects an enum value it does not know. The collector, a GHCR image and a Go module, is versioned on its own, starting at `v0.1.0` cut just before the agent's `0.1.0`, and released when its code or its bindings change. Its support rule is "run the latest collector". Each repository declares its version in its tree (`gradle.properties` here) and a release is a matching `vX.Y.Z` tag.

Nothing in any repository reads, compares or orders the collector's version: it appears only in its startup log. What couples the collector to the agent is the schema. With redaction off, Go keeps unknown fields and a newer agent's payload passes through an older collector intact; with redaction on, an older collector strips the newer fields and marks the run stripped (collector ADR 0005), which is safe and judged by nothing. A newer collector serves every older agent, since `buf breaking` keeps the schema additive. So "the latest collector" is always a compatible one, and the table ADR 0057 rejected separate versions for is never needed.

The one ordering rule is about bindings, not versions: before an agent release that changed the schema reaches anyone, the server's `master` and the latest collector release must carry bindings at least as new as that schema. The server needs them whether or not a collector sits in between, since it ingests through the collector's `ingest` package with its own pinned bindings and would otherwise ignore a new field, and a field a finding depends on would give a wrong finding. The agent's release script checks both (ADR 0063).

## Considered options

- **Lockstep for all three (ADR 0057).** Rejected on the research: the collector would release an empty image and Go tag for every agent-only change, the release would span two repositories with partial failures to handle, and at agent `1.0` the collector's importable `ingest` and `metrics` packages would take on a Go compatibility promise, at `2.0` a `/v2` module path, rewriting the server's imports though the collector's API had not changed.
- **One number, the collector skipping versions it does not change.** Rejected: once the numbers drift, "a collector at least as new as the agent" no longer names a compatible build, which leaves the rule chosen here.
- **Starting at `1.0.0`.** Rejected as in ADR 0057: nobody outside the project has written a test against the public surfaces yet, and `0.x` leaves room for what the first adopters find. The ABI dump still makes each break deliberate, and `buf breaking` binds the wire from the first agent in the field.
- **Deriving the version from the git tag with a plugin.** Rejected as in ADR 0057: the version would be readable nowhere in the tree.

## Consequences

- The collector's redaction WARNING, its README and its ADR 0005 say "upgrade to the latest collector" rather than "to the agent's version".
- A schema change is not finished until the server and the collector carry its bindings; the release script refuses an agent release that would get ahead of either, with an override for a change no consumer reads.
- `agent_version` stays opaque and compared only for equality (ADR 0054, amended). Nothing orders versions, so nothing constrains how either line is numbered.
- The JDK floor is 17: the agent jar and the testkit are class-file version 61 and compile against the JDK 17 API, and CI runs the suite on JDK 17, 21 and 25. The JDK 17 leg passed with no change to the agent; the lambda factory's members ADR 0035 reads have the same names on 17.
