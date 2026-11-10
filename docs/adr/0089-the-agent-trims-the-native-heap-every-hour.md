---
status: accepted
---

# The agent trims the native heap every hour

Decided on 2026-11-09, after the soak test. Luke chose the default and the option.

A JVM with the agent grows in resident set size against a twin with no agent, for days. On `soak-1`, after six days, shop-2 sat at 458 MB against its twin's 275 MB, shop-3 at 388 MB, and events 1.0.0 at 351 MB against 211 MB. The twins held flat from the second day. The growth came in steps of 15 to 25 MB, the last ones on days 4 and 6.

The memory in use does not grow. `jcmd System.native_heap_info` splits the resident set into memory outside malloc, malloc in use, and memory glibc holds free in its arenas. Over two days shop-2's malloc in use held at 33 MB and its memory outside malloc at 251 MB, while the free memory glibc held rose from 144 to 174 MB. `jcmd System.trim_native_heap` on shop-3 cut it from 386 to 319 MB.

The free memory comes from C2 compiles. One compile can use 37 to 61 MB of native memory and free it when it ends. glibc keeps freed memory in the arena it came from, and a compiler thread allocates from its own arena, so each compile that peaks higher in an arena than any before it leaves a step. A JVM with no agent does this too: the twin stepped 22 MB after its first reports call. It flattens once its code is compiled, on the first day. The agent keeps the JIT busy for days, because its export code runs once a minute and reaches the C2 thresholds slowly. In 12 hours on days 2 and 3, shop-2 made 89 C2 compiles and its twin 14.

## Decision

- **The agent trims the native heap once an hour.** It calls the JVM's `System.trim_native_heap` diagnostic command through the `DiagnosticCommand` MBean. The command calls glibc's `malloc_trim`, which returns free memory to the system. It does not touch memory in use.
- **`trimNativeHeapIntervalMinutes` sets the interval**, from 0 to 1440, with 60 as the default. 0 turns the trim off.
- **The first trim comes one interval after startup.** Startup stays as the overhead page measures it, and the host has configured its logging before the agent first touches JMX.
- **The agent leaves the heap to the JVM when the JVM trims already.** If `-XX:TrimNativeHeapInterval` is set above 0, the agent never trims.
- **The trim turns itself off for good** outside Linux, when the JVM has no such command or lacks the `java.management` or `jdk.management` module, when the command says it is not available (a C library other than glibc), and after any failure.

## Considered options

- **Leave it to the JVM's flag and document it.** This was the decision of 2026-11-07, when the growth looked bounded after three days. The steps on days 4 and 6 showed it was not. A service that never reads the overhead page would see the growth and blame the agent.
- **`MALLOC_ARENA_MAX=2`.** Rejected: it is an environment variable glibc reads at process start, so the agent cannot set it. In a 12-hour test it slowed the growth (320 MB against 354 MB) but did not stop it.
- **Make the agent's code compile less.** Two cuts to the flush path, a cached frame-weight array and a fixed pool of two HTTP threads, moved neither the compile count nor the resident set, and were reverted. Any code that runs rarely compiles late, the host's included, so a cut cannot remove the cause.
- **Trim after every flush.** Rejected: a trim walks every arena and holds its lock. Once a minute adds work and contention for a few megabytes more than once an hour returns.

## Consequences

- In a 12-hour test of the shop service, an agent JVM trimmed every 5 minutes ended at 304 MB. The same agent without a trim ended at 354 MB, and two JVMs with no agent at 320 and 327 MB.
- A trim takes a few milliseconds and blocks malloc in each arena while it trims that arena. In the shop service on `soak-2` a trim every minute took 1.8 to 5.2 ms and returned 6 to 22 MB.
- The resident set size grows back between trims as the JIT compiles, and the hourly trim holds it level. In a 24-hour test of the shop service on `soak-2`, measured from the first trim, two JVMs with the hourly trim averaged 335 and 339 MB and held level. Two without it averaged 360 and 361 MB and grew from 347 to 364 MB, and two with no agent averaged 311 and 328 MB. Each trim after the first took 4.5 to 8.0 ms and returned 49 to 64 MB.
- Two JVMs on the release build trimmed 12 times each in 12 hours, in 4.5 to 10.6 ms a trim, with no error.
- The agent loads the platform MBean server on its first trim, which the host may not otherwise load.
