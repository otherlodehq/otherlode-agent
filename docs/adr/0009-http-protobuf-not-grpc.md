---
status: accepted
---

# HTTP and protobuf rather than gRPC

Payloads are protobuf messages posted over HTTP/1.1 keep-alive with the JDK's built-in `java.net.http.HttpClient`, as `application/x-protobuf`. Both options serialise with protobuf, so the wire cost is the same. gRPC's advantage is HTTP/2 multiplexing and streaming, which matters at high RPC rates and not for one batch POST per instance per minute. gRPC's cost for a javaagent is grpc-java plus Netty shaded into every customer JVM, with collision risk against applications that already use gRPC.

## Consequences

- No extra runtime dependency for transport. ByteBuddy, protobuf-java and the Kotlin stdlib are relocated in the shaded jar for the same collision reason.
- Retries cover only failures that describe the server's state: connection and timeout errors, any 5xx, 408 and 429. Every other 4xx fails at once, since resending the same bytes cannot change the answer.
- Revisit gRPC only if continuous streaming ever replaces periodic batching.

## Amended on 2026-10-07: a refused delta or manifest is offered again on every flush

Decided in the grill on the customer docs round's follow-ups. "Fails at once" holds within one send only. A delta batch or manifest chunk the collector refuses, whatever the status, is computed again and sent on the next flush, and on every flush after it, until one is confirmed. That is deliberate for 400 and 413 too. The collector answers 400 for bytes it cannot read, for a missing resource, service name, instance id or run id, for a service name or namespace of `.` or `..`, and for a body read that failed partway. The last is transient, though it rarely reaches the agent, whose own send has usually timed out or lost its connection by then; it answers 413 above a fixed 16 MiB, and a 413 from a reverse proxy in front of it clears once its operator raises the limit, with no agent restart. A delta is the gap between the hit totals and the last confirmed totals, recomputed each flush, so resending costs a refused request or two per flush and no memory, and each attempt carries the hits since, not the bytes that were refused. A static baseline chunk is the same bytes on every attempt, which is why ADR 0014 drops a scan on a 400.

Considered: dropping a refused payload and reporting the loss later (the report can be refused for the same reason, and a dropped delta advances the confirmed totals, so a probe hit only in it reads as never hit, the silent absence this project rules out); splitting on 413 (helps 413 only, and a class is never split across batches); disabling the agent on a refusal (a transient 400 or a proxy fixed while the process runs would lose the whole process). How a collector learns that a run's counts are behind is ADR 0068. The static baseline keeps its own rule (ADR 0014).

## Amended on 2026-10-28: the collector's full list of refusals

The 2026-10-07 amendment listed the collector's 400 and 413 answers as they stood then. The collector refuses more, and the rule above holds for each: a refused delta batch or manifest is offered again on every flush.

- **400** also answers an identity string (`service_name`, `service_version`, `service_instance_id`, `environment`, `run_id`, `service_namespace`, `agent_version`) longer than 1024 bytes or holding a NUL byte; an agent time in a delta batch or manifest outside the start of 1970 to the end of the year 9999; and a class that failed to initialise with no reason. This agent sends none of these: it skips a configured identity value over 1024 bytes or holding a NUL, stamps every time with the clock and always sends a reason.
- **413** also answers a payload over its kind's element limit (collector ADR 0008), counted before the body is decoded.

The collector's `data-sent` page lists every check in the order it runs them.
