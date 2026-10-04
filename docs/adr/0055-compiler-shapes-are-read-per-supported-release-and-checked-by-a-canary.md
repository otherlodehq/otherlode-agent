---
status: accepted
---

# Compiler shapes are read for a declared list of compilers, kept honest by fixture builds and a canary

Decided on 2026-10-03 with ADR 0054. Every exact-body rule was read off `javap` output from one compiler each: kotlinc 2.2.21, scalac 2.13.15 and 3.3.4, and javac 21, with javac 17 only simulated by rewriting javac 21's bytes. Nothing failed when a compiler changed a shape, and scalac changed several in patch releases (ADR 0054). Reading compiler versions stays part of the answer, but as a declared list with tests behind it rather than as they appear.

## The design

- **The list.**
  - kotlinc 1.9.25, 2.1.21, 2.2.21 and 2.4.20. 1.9 is K1 and still in use; 2.1.21 stands for the releases whose default is `-jvm-default=disable`.
  - javac 17, 21 and 25 through Gradle toolchains, replacing the simulated javac 17.
  - scalac 2.12.x, 2.13.14 onwards, and Scala 3 from 3.3.3 to 3.9.0. Every body variant found in the 2026-10-03 sweep is read.
- **One fixture build per variant boundary, not per release.** Each asserts that every plumbing method is marked, every machinery jump dropped, and nothing hand-written marked. Roughly: Scala 2.12.20, 2.13.16, 2.13.18, 3.3.6, 3.3.7, 3.3.8, 3.7.0, 3.7.2, 3.8.3, 3.8.4 and 3.9.0, plus each kotlinc and javac above.
- **An exact table for Scala 3.** ADR 0054 keys Scala 3 on the release named in the `.tasty` header, so the agent ships a table from each read release to its shape variant. A sweep script compiles the fixtures with every release and fills it in; untested releases (3.4.0 to 3.4.2, 3.5.0 to 3.5.1, 3.6.0 to 3.6.3 on the day) are swept before the table ships. Release candidates are never read.
- **A canary.** A scheduled CI job compiles the fixtures with the newest release of each compiler. A shape change fails a build instead of an adopter's report, and the new release is read, added to the table and given a fixture build if it is a new variant.
- **Published.** The README says which compilers' marks are exact, from the same list.

## Considered options

- **Ad hoc**, ADR 0048's stance. Rejected: nothing notices a change until an adopter does.
- **Ranges for Scala 3** (any release between two read ones with identical bodies counts as read). Rejected: it assumes a change cannot land in an untested patch and revert by the next, and the sweep that removes the assumption takes minutes.
- **A fixture build per release.** Rejected as build time spent proving identical bytes; the table records the per-release fact and the builds cover each variant.
- **Reading only the two Scala 3 LTS lines**, 3.3 and 3.9. Rejected: the variants in between are few and mechanical, and an adopter on 3.7 would otherwise get unread shapes for code the agent could read.

## Consequences

- Compiling one Gradle build's fixtures with several kotlinc releases needs KGP's Build Tools API `compilerVersion` or a compile task run by hand. The first chunk that builds the Kotlin matrix confirms which works.
- Scala 2 has no table, since its classes do not name the compiler: a new 2.12 or 2.13 release that changes a shape reads as unread shapes (ADR 0054) until the canary catches it and the agent reads it.
- The 2026-10-03 sweep's outputs and scripts were scratch work; the sweep script the table needs is written fresh and kept in the repo.

## Amended on 2026-10-04: a list of releases, and the sweep is the matrix test

The table of read Scala 3 releases is a list, `scala3-read-releases.txt` in the agent's resources, not a map from release to shape variant. The matcher reads every variant as a union (ADR 0048, amended), so the only question the agent asks of a release is whether it was read, and a variant tag per release would answer nothing it uses. The cost is the one ADR 0048 already accepts: a class from one read release with a hand-written method in another read release's exact shape is marked generated.

The sweep is the compiler matrix test run with the release list overridden, `-Potherlode.matrix.scalac=<releases>`, with gradle.properties' Scala 2 releases kept in the list. It passed for every release from 3.3.3 to 3.9.0 on 2026-10-04, which is what the list holds. A test requires every Scala 3 release in the matrix to be on the list.

The matrix compiles with `-release 21`. Scala 3.3 with no `-release` targets Java 8 bytecode, where an enum's `valueOf` and `fromOrdinal` build their message with a `StringBuilder` rather than string concatenation, a shape not read; those two methods then report as unread shapes (ADR 0054 holds them, scalac refusing hand-written ones), never as dead code. Every other plumbing body was the same at both targets. Reading that shape, and a matrix entry for the default target, are recorded in `STATUS.md`.

