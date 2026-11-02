---
status: accepted, amends ADR 0068 and ADR 0082
---

# A run is pending until its class loading settles

Decided on 2026-11-02, after the soak test's second smoke run.

Server ADR 0087 holds never-loaded claims back while a live run that declares the class has not settled. A run settles with its first flush whose payloads say nothing is pending (ADR 0068, ADR 0082). The first flush comes at a random point in the first flush interval, so a fleet does not flush at once, and it can come within a second of the JVM's start. In the second smoke run, shop-1's agent flushed 0.6 seconds after the JVM started. Its manifest, its first delta batch and its complete scan all arrived before Spring had loaded the application's classes. The run read as settled, and a read 4 seconds later claimed every class in its scan never loaded, every startup route never called, and every dependency unloaded. The same happened after the events upgrade, for the classes new in 2.0.0. The claims lasted until the next flush, 10 seconds at the smoke's scale and up to a minute at the default. A snapshot taken then kept them until its next refresh.

ADR 0087 accepted a one-flush lag for a class that loads in the middle of a settled run. It did not foresee that the first flush can come before the application starts.

## Decision

- **A run's counts are pending until its class loading settles.** From the agent's start, every payload says its counts are pending, with the agent's start as the time, until a flush that starts at least one flush interval after the agent started finds no in-scope class loaded since the previous flush started. In-scope classes are the ones the agent registered, skipped, found nothing to probe in, or found unreported. That flush and every later one say what ADR 0068 and ADR 0082 say.
- **Settling has a cap.** A run that keeps loading in-scope classes stops counting as starting after 10 flush intervals. A class that first loads after that is the one-flush lag ADR 0087 accepts.
- **Nothing else changes.** The server already withholds every zero-count claim of a run that is not settled, and makes the service's snapshots due at once when it settles (ADR 0087).

## Considered options

- **Delay the first flush by a full interval.** Rejected: an application that takes longer than one interval to start settles before it has loaded its classes, and the instance list shows nothing for a minute.
- **Hold claims on the server for a run's first N flush intervals.** Rejected: the server cannot tell an application that started in 2 seconds from one that takes 3 minutes, and only the agent sees its classes load.
- **Count every class the JVM loads.** Rejected: a busy JVM keeps defining lambda forms and reflection accessors, so it would never settle.

## Consequences

- A new instance reads as counts behind for at least one flush interval, and until its in-scope classes stop loading. Its zero-count claims, never loaded included, wait for it.
- A rolling deploy holds a version's never-loaded claims until its first instance settles, as ADR 0087 already holds them while it has not.
- A run whose application loads in-scope classes on every flush for 10 intervals settles at the cap.
