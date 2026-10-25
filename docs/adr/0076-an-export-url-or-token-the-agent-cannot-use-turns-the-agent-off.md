---
status: accepted
---

# An export URL or token the agent cannot use turns the agent off

Decided on 2026-10-24 in a grilling session that followed a security audit of the agent.

When the agent rejects `exportUrl`, it logs a WARNING and sends to `http://localhost:4319` with the configured token. The operator never chose that address, and the token and the payloads travel to it in clear. A token that holds a character the JDK refuses in a header value fails every send, and the JDK's exception message holds the whole header value, so the token reaches the agent's log on every flush.

## The design

- **A rejected `exportUrl` turns the agent off.** The agent logs an ERROR that names the setting and its source, not the value, and then instruments and sends nothing in this JVM, as when the configuration disables it. The default `http://localhost:4319` applies only when `exportUrl` is not set. A URL with a user name or password is refused too: the JDK's HTTP client never sends one, and the URL is in the message of every failed send.
- **A token outside visible ASCII turns the agent off.** After trimming, a token must hold only the characters `!` to `~`. Otherwise the agent logs an ERROR that names the source of the token, not its value, and turns off the same way.
- **The ERROR comes from the start.** The agent logs each refusal when it starts, after it checks `enabled`, so an agent the configuration disables logs none.
- **No header value reaches a log.** The exporter replaces the message of an `IllegalArgumentException` from the request builder with one that names the header and not its value.
- **An identity value over 1024 bytes is skipped.** A service name, namespace, version, environment or instance ID longer than the collector's 1024-byte limit is skipped with a WARNING that names its source, the same way a `.` or `..` is, so the next source can give the value. The collector would refuse every payload that carried it.

## Considered options

- **Fall back to the default URL without the token.** Rejected: the payloads still go to an address nobody chose, in clear.
- **Keep instrumenting with export off.** Rejected: the woven classes would cost time and memory, and nothing would read their counts.
- **Stop the JVM.** Rejected: the host application is not at fault. With the agent off, the instance never reaches the collector, so it shows as missing, not as an instance with zero hits.

## Consequences

- An instance with a mistyped URL or a damaged token runs without the agent until it is fixed and restarted. The ERROR names the setting to fix.
