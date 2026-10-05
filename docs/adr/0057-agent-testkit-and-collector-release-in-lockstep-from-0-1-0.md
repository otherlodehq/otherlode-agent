---
status: superseded by ADR 0062
---

# The agent, the testkit and the collector release in lockstep, from 0.1.0

Decided on 2026-10-04 in a grilling session with Luke, as `STATUS.md`'s pre-release checklist item 4.

Two artifacts go to Maven Central under the `dev.otherlode` namespace, verified by a TXT record on `otherlode.dev`: `otherlode-agent`, the shaded `-javaagent` jar, and `otherlode-testkit`. Wire, bootstrap and the endpoint modules ride inside the agent jar and are not published. The collector ships as a GHCR image and its Go module. All three share one version number and are released together, even when one of them has not changed: ADR 0056 already requires the testkit to match the agent, and collector ADR 0005's skew rule reads simply as "a collector at least as new as the agent". The first release is `0.1.0`. The version is declared in each repository's tree (`gradle.properties` for the agent), a release is a `vX.Y.Z` tag, which Go modules require anyway, and the release workflow refuses to publish when the tag and the declared version differ.

## Considered options

- **Versioning the collector on its own.** Rejected: which collector goes with which agent would need a table, and the skew rule would be stated in terms of it.
- **Starting at `1.0.0`.** Rejected: item 4 settled the public surfaces, but nobody outside the project has written a test against them yet, and `0.x` leaves room for what the first adopters find. The ABI dump still makes each break deliberate. The wire is bound by `buf breaking` from the first agent in the field whatever the number says.
- **Deriving the version from the git tag with a plugin.** Rejected: the version would be readable nowhere in the tree.

## Consequences

- A collector release with no changes of its own is normal.
- `agent_version` stays an opaque string compared only for equality (ADR 0054, amended). Nothing orders versions, so nothing constrains how they are numbered.
- Publishing, signing and the image push are release mechanics, `STATUS.md`'s checklist item 6, and so is the JDK floor: 17 if a JDK 17 CI leg passes.

## Superseded on 2026-10-05

Research for checklist item 6 found nothing in any repository that reads the collector's version, and that the newest collector serves every agent, so no pairing table is needed. ADR 0062 keeps the agent and the testkit on one version and gives the collector its own; everything else here stands there.
