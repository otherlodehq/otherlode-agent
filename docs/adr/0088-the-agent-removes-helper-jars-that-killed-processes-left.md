---
status: accepted
---

# The agent removes helper jars that killed processes left

Decided on 2026-11-06, after the soak test. Luke asked for the fix.

The agent copies its bootstrap holder jar, about 25 KB, to a temp file in `java.io.tmpdir` and appends it to the bootstrap class loader. It asked the JVM to delete the file on exit. That runs only on a graceful exit. A `kill -9`, an out-of-memory kill or a crash leaves the file behind, and on Windows the delete fails even on a graceful exit, because the JVM still holds the jar open. soak-1 held 12 such files after four days: one for each killed process, plus the live ones. A service that crash-loops on a host whose temp directory persists gains one file per crash. A container that restarts usually gets a fresh temp directory, so there it rarely builds up.

## Decision

- **The file keeps its random name.** `Files.createTempFile` creates it with a random name, owner-only permissions, and an exclusive create.
- **A running JVM holds a lock on a file of its own.** Next to its jar it creates `<jar>.lock`, locks it before writing the jar's bytes, and keeps the lock for the life of the JVM. The process never opens that file again. On Linux and macOS a Java file lock belongs to the process, and closing any descriptor of the locked file releases it, so a lock on the jar itself would drop the first time the JDK opened and closed the jar. The operating system releases the lock when the process dies, however it dies. Outside Windows, both files are marked for deletion at exit, the jar first. On Windows neither is, since the open jar cannot be deleted and a lock file deleted without its jar would leave the jar unsweepable.
- **The first scheduled flush sweeps leftovers once.** On the export thread, the agent deletes each `otherlode-bootstrap-*.jar` in the temp directory that is not its own, was last modified at least 10 minutes ago, and has a lock file nobody holds, and then deletes that lock file. A jar with no lock file stays, since nothing proves it abandoned. A stale lock file whose jar is gone is deleted too. The sweep skips any file it cannot read or delete, and it never throws. The shutdown flushes skip it, so a JVM that exits before its first scheduled flush sweeps nothing and never spends their deadline on it.

## Considered options

- **A fixed name, such as a hash of the jar's contents, reused by every start.** Rejected: on a temp directory shared between users, another user could create that path first with a jar of their own, and the agent would put it on the bootstrap class path. Making that safe needs a private per-user directory with owner and permission checks, which costs more than the leftover files.
- **Sweep by age alone.** Rejected: a long-running JVM's jar is old too. On Linux and macOS, deleting it is mostly harmless once the JVM has opened it. But the JDK reads a resource from a bootstrap-appended jar by its path, so a deleted jar can still break a later lookup.
- **Lock the jar itself.** Rejected: writing the jar's bytes, or the garbage collector closing a `JarFile` on it, closes a descriptor of the jar and releases the lock. A test run on macOS with JDK 22 and on Linux with Temurin 21.0.13 showed another process taking the lock after one such close.
- **Sweep in `premain`.** Rejected: listing the temp directory adds to startup, which the overhead page measures.

## Consequences

- A killed JVM's jar and lock file stay until a later agent's first scheduled flush comes at least 10 minutes after they were written. On Windows the same holds after a normal exit.
- A jar left by an agent build before this record has no lock file, so no sweep deletes it. No release shipped before this record.
- A running JVM keeps two files in the temp directory: its jar and its lock file.
