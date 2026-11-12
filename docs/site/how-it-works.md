---
title: How the agent works
description: How the agent counts in the class it instruments, pushes cumulative totals to Otherlode, and leaves your classes loading as they would without it, so you can judge whether to run it in production.
order: 140
---

## What the agent records

The agent counts how often each method runs, which way each conditional goes, and how often each endpoint is matched. It reports what it observes, such as these counts and the classes it could not count. It carries no threshold, no "dead" flag and no confidence score.

A count of zero means the code did not run in the processes the agent watched, over the time it watched them. Otherlode puts every run of every instance together and judges which code no run reached. See [how findings are judged](/docs/server/how-it-works).

Where the agent cannot count something, it reports that it did not count it, so missing data never reads as a zero. The sections below show where.

## Probes are woven in when a class loads

The JVM hands the agent each class's bytes just before it defines the class. For each class that matches your include rules, the agent adds counters, called probes, such as one at the entry of each method and one on each outcome of each conditional. The JVM then defines the changed class, and your code runs as usual.

Include rules are required. With none set, the agent logs an ERROR and sends nothing. See [configuration options](configuration) for `includePackages`.

The agent starts only with the `-javaagent` flag at JVM startup. It does not attach to a JVM that is already running. See [attach the agent](attach).

## Each class owns an array of counts

Every instrumented class gets one array of 64-bit counters, with one slot per probe. Once a probe has its class's array, each hit is one increment of its slot, with no lock, lookup or allocation.

The increment is not atomic. When two threads hit one probe at once, one increment can be lost, so counts are approximate and can step backwards between two flushes. No race can return a probe that ran to zero, because the lowest value an increment writes is one. That holds on 64-bit HotSpot, which reads and writes an aligned 64-bit value in one step.

How a probe finds its class's array depends on the class-file version:

- From class-file version 55 (Java 11), the agent adds only the probe instructions. Each probe loads the array through a constant that the JVM resolves when the probe first runs.
- Below 55, the agent adds a static field and small accessor methods to the class.

Neither route throws a `NullPointerException` when a superclass's initialiser runs your class's code before your class has finished initialising. If the agent cannot find a class's array, the probe counts into a spare array, and the agent logs a warning once for that class:

```text
otherlode: no registered probe array for com.acme.OrderService; its hits will not be counted (resolver installed: true)
```

## Results are pushed on a fixed interval

The agent pushes its data to `exportUrl`, which is Otherlode's ingest or your collector, every `flushIntervalSeconds`, 60 seconds by default. It opens no port of its own. Each instance waits a random offset before its first flush, then flushes at a fixed rate, so a fleet that starts together does not send on the same tick.

Every flush sends a delta batch, even when no count changed, so Otherlode can tell an idle instance from one that has stopped. A flush also describes each newly loaded class: which class, method and line each probe is. The agent sends each description once.

The transport is HTTP/1.1 with a protobuf body, through the JDK's own HTTP client. See [what the agent sends](data-sent) for each payload and what happens when the receiver is down.

## Totals are cumulative, and each process is a run

Each update carries a probe's total since the process started, not the growth since the last flush. A payload sent twice carries the same totals both times, so the agent keeps no queue of payloads. It moves its record of what was delivered forward only after the receiver confirms a send, and after a failed send the next flush reports the live totals again. The memory this takes depends on the size of your code, not your traffic, so an unreachable receiver does not make it grow. A process that dies during an outage loses the counts since its last confirmed flush.

One send makes up to 5 attempts. [Retries inside one send](data-sent#retries-inside-one-send) lists which failures it retries and how it reads `Retry-After`.

A restarted process counts from zero again. The agent makes a random run ID at every start and stamps it on every payload, and Otherlode keeps each run's data apart. The instance ID is a fresh random value per process unless you pin it, for example to a pod name. A restart under a pinned instance ID is a new run.

Outside a [test run](test-runs), on a graceful exit the agent flushes at once, waits for your application's own shutdown hooks to end, up to 18 seconds into shutdown, then sends a final flush marked as the last one. The whole shutdown takes up to 28 seconds. `kill -9` and a crash send no final flush. [When payloads are sent](data-sent#when-payloads-are-sent) lists the steps.

## Shape comes from the class file, probes go in last

The agent works out what a method is by reading its bytecode: which conditions are code you wrote, which jumps are compiler plumbing, and which methods a compiler generated. It reads this shape from the class file, the bytes the compiler wrote, through the class's own loader. Another agent that rewrote the class first, such as JaCoCo, does not change the result.

Every transformer of the agent that changes a class is retransformation-capable, so the JVM runs them after every transformer that is not, whatever order the `-javaagent` flags come in. JaCoCo's agent, AspectJ's weaver and Spring's load-time weaver are not retransformation-capable, so the agent always runs after them. It adds its probes to the bytes it receives, so their changes stay. Where the received bytes no longer line up with the class file for a method's conditionals, the method keeps its entry probe and gets no branch probes. See [other agents in the same JVM](compatibility#other-agents-in-the-same-jvm).

## A woven class behaves like the original

A woven class loads, fails and reflects as the same class does without the agent, apart from counting.

- The agent keeps the class's own verification frames and writes new ones only where it inserts code.
- From class-file version 55, the agent adds no field or method, so reflection over your classes returns what it does without the agent.

When the agent cannot instrument a class, it skips the class, logs why, and reports it as skipped with the reason. A class whose superclass or interface cannot be found is skipped, since the JVM cannot define it either. See [skipped classes](classes#skipped-classes).

A class can be woven and then fail to load, for example when a dependency is missing. The agent holds that class's probes back from its reports until it confirms that the class exists, for example by a count above zero or by the JVM listing the class as loaded. A class it gives up on is reported as failed to load, not as code that never ran. The routes a JAX-RS resource class declares are held back in the same way. See [failed to load](classes#failed-to-load).

If the agent cannot start safely, it turns itself off and sends nothing, so the instance is missing from Otherlode instead of reporting zero everywhere. It does this, for example, when no include rules are set, when `exportUrl` or `authToken` holds a value it cannot use, or when it cannot install the class it needs in the bootstrap class loader. Each case logs an ERROR, such as:

```text
otherlode: could not install the bootstrap holder; the agent is disabled for this JVM
```

When a tool such as an IDE redefines a woven class, the agent refuses if the class file differs from the one it wove, or if the new bytes arrive at a class-file version that needs another probe form. The JVM then rejects the redefinition, and the class keeps running with its probes. Otherwise the agent weaves the new bytes with the probes of the first weave. See [HotSwap and IDE redefinition](compatibility#hotswap-and-ide-redefinition).

## What this costs you

- One restart, to add the `-javaagent` flag.
- Approximate counts. Use them to ask whether code ran, not exactly how many times.
- Memory outside the Java heap. Because the transformers that weave your classes are retransformation-capable, the JVM keeps a copy of each woven class's received bytes.
- The counts since the last confirmed flush, if a process dies during an outage.
- Descriptions sent once. After the receiver stores a class's description, the agent frees it from memory. If the description is lost after that, Otherlode gets it again only from a new run.

See [overhead](overhead) for measured costs.
