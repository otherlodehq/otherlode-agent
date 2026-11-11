---
status: accepted, amends ADR 0008, ADR 0010, ADR 0018 and ADR 0091
---

# The agent can send straight to Otherlode, and only a 202 is stored

Decided on 2026-11-11 with Luke, before the first release. Server ADR 0096 holds the server's half and the reasons for a path without a collector.

`exportUrl` may name a collector or Otherlode's ingest, `https://ingest.otherlode.dev`. This repo calls either one the receiver. Ingest takes the same four paths, media type and size limits as a collector, since the server runs the collector's own ingest handler.

## Decision

- **One token option.** `authToken` is the bearer token for the receiver: a collector token, or an ingest key when the agent sends straight to ingest. No second option is added.
- **`exportUrl` keeps its default of `http://localhost:4319`.** An agent with no setting sends nothing off the machine.
- **Only a 202 counts as stored.** Any other 2xx fails the send. The agent logs the status, keeps its counts and sends again at its next flush. A collector has answered only 202 for a stored payload since collector ADR 0010, and the agent now holds the receiver to it. Before, a proxy or a wrong URL that answered 200 marked payloads delivered, released manifests nobody stored (ADR 0008) and set `manifest_confirmed` (ADR 0091) for a manifest the server never had. The testkit's collector and the demo's stub answer 202.
- **The agent logs the ingest notices.** It reads `Otherlode-Notice` from a 202 and logs one WARNING per notice and service at most once an hour, as the collector does (collector ADR 0009). It still reads no response body.
- **The oversize skip reason names the ingest limits,** not the collector's. The reason is a wire string, so it is fixed before the release.
- **No proxy option.** The agent's HTTP client uses the JVM's default proxy selector and default TLS context (`HttpClientImpl` in JDK 22, lines 461 and 485 to 487), so `https.proxyHost` and the JVM's truststore apply. A network whose JVMs cannot reach the internet sends through a collector.
- **The retry policy does not change:** five attempts with backoff inside one send, and `Retry-After` up to 2 seconds. The OTLP exporter specification retries the same way.

## Considered options

- **An `ingestKey` option beside `authToken`.** Rejected: two names for one header would both freeze at the release, and a reader would have to pick one.
- **A default `exportUrl` of `https://ingest.otherlode.dev`.** Rejected: an agent with no configuration would send data off the machine, and changing a default after the release breaks every collector user.
- **Any 2xx counts as stored.** Rejected: see the 202 decision above.

## Consequences

- ADR 0008, ADR 0010 and ADR 0018 say "a 2xx" for a delivered payload. Read it as a 202.
- ADR 0091 says the collector confirms a manifest. Read it as the receiver.
- `release.sh` still checks the collector's latest release. Collector users still need its bindings at least as new as the agent's schema.
