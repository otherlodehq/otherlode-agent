---
status: accepted
---

# A release is cut locally and published from its tag

Decided on 2026-10-05 in a grilling session with Luke, as `STATUS.md`'s pre-release checklist item 6, with ADR 0062. A version on Maven Central can never be changed or removed, so every step before the upload is built to fail early and cheaply.

`scripts/release.sh X.Y.Z` runs on Luke's machine with his git identity. It refuses unless the tree is clean on `master`, `CHANGELOG.md` has a `## [X.Y.Z]` section, and, when the proto changed since the last agent tag, the server's `master` and the latest collector release both pin BSR bindings at least as new as the agent's last proto commit (read through `gh api`, with an override flag for a change nothing reads). It then commits `version=X.Y.Z`, tags `vX.Y.Z`, commits the next `-SNAPSHOT`, and pushes. Write access to `master` stays out of CI.

The tag starts the release workflow. It refuses a tag that differs from the declared version or names a SNAPSHOT, runs the full build on every JDK leg, and uploads one Central Portal deployment holding both artifacts, through GradleUp's nmcp with `maven-publish` and `signing`. Both publish the shadow component: the only one whose POM lists no bundled dependency and whose main jar is the shaded one. The agent's javadoc jar is a README, which the Portal accepts for code with no API; the testkit's is Dokka HTML, and kotlin-stdlib, which its shaded jar excludes, is declared on its shadow variant so the POM names it without a `Class-Path` in the manifest. For `0.1.0` the deployment is `USER_MANAGED`: the Portal validates it and holds it until Luke publishes or drops it by hand, and the GitHub release stays a draft until he has. Later releases are `AUTOMATIC`, switched by a repository variable. The GitHub release carries the agent jar, its `.asc` and `SHA256SUMS`, all copies of what Central holds, with the changelog section as its body.

The signing key is a dedicated one for `releases@otherlode.dev`, expiring after two years, its public half on keys.openpgp.org and keyserver.ubuntu.com, its primary key and revocation certificate in Luke's password manager. The key, its passphrase and the Portal token live in a GitHub environment `release` that only `v*` tags may use, and a ruleset lets only Luke push a `v*` tag.

A rehearsal workflow, run by hand, builds at `0.0.0-rehearsal.<run number>`, uploads it `USER_MANAGED`, waits for the Portal to validate it, and drops it through the Portal API, with no tag and no GitHub release. It proves the Portal accepts the bundle without spending a real number, and it runs again whenever the publishing configuration changes.

## Considered options

- **Every release `USER_MANAGED`.** Rejected: once the pipeline is proven, the tag is the deliberate act, and a click per release adds only a way to forget it.
- **A required approval on the `release` environment.** Rejected: it repeats the Portal's gate for `0.1.0` and adds friction afterwards.
- **Trusting `master`'s CI run for the tagged commit.** Rejected: the workflow builds the commit it publishes, whatever ran before, and a flaky test then blocks a release, which is the safe direction.
- **The workflow pushing the post-release SNAPSHOT bump.** Rejected: it needs a token that can write to `master`.
- **vanniktech's plugin.** Rejected: it has no Shadow support (its issues 1123 and 1424 are open), and its releases from 0.36 need Gradle 9. JReleaser does far more than two jars need, and a hand-written Portal upload would repeat what nmcp does.
- **Rehearsing with `0.1.0` and dropping it.** Rejected: the Portal's docs do not say a dropped deployment frees its version. Two rehearsals at one number settle the question.
- **Publishing SNAPSHOTs.** Not now: there are no adopters, and every SNAPSHOT build shares one version string, which the testkit's equality check cannot tell apart.
- **Signing with Luke's personal key.** Rejected: a leaked CI secret should revoke a key that signs nothing else.

## Consequences

- The collector repository has the same shape: its own `scripts/release.sh`, `CHANGELOG.md` and tag workflow, publishing a multi-arch image (collector ADR 0006).
- Publishing depends on `verifyAgentJar` and `verifyTestkitJar` explicitly; a finaliser alone does not order them before the upload.
- Luke's steps, which no workflow can take: the `releases@otherlode.dev` mailbox, the Portal account, the namespace and its TXT record, the Portal token, the signing key and its upload, the `release` environment's secrets, and the tag ruleset.
