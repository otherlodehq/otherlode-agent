---
title: What the agent sends
description: The requests the agent makes, what each payload contains, when it is sent, what it never contains, and what happens when the collector is down.
order: 130
---

## Summary

The agent pushes three kinds of payload to the collector you set with `exportUrl`. Every payload is a protobuf message, and a payload names code (classes, methods, source files, lines, conditions, routes, jars) and counts. It carries no request data and no argument or return value.

The agent has no redaction setting. It sends what this page lists. A collector can remove fields before it forwards them, so a deployment that needs redaction sends through a collector with redaction switched on. An agent that posts straight to a backend sends everything in clear.

## Requests

Every request is a `POST` to a path under `exportUrl`. The agent drops a trailing slash from `exportUrl` and refuses a query string or fragment, so the paths below always sit directly under the URL you set. The default `exportUrl` is `http://localhost:4319`.

| Path under `exportUrl` | Payload | Sent |
|---|---|---|
| `/v1/otherlode/deltas` | Delta batch | Every flush, even when empty |
| `/v1/otherlode/manifest` | Probe manifest | On a flush that has something not yet delivered |
| `/v1/otherlode/static-baseline` | Static baseline chunk | Once per process, when `staticBaselineEnabled` is on |

Each request has these headers:

| Header | Value |
|---|---|
| `Content-Type` | `application/x-protobuf` |
| `Authorization` | `Bearer <authToken>`, only when you set `authToken` |

The JDK's `java.net.http.HttpClient` adds its own headers (`Host`, `Content-Length`, `User-Agent`). Requests use HTTP/1.1, and the agent offers no upgrade to HTTP/2. It sends the token on every request and logs one warning at startup when `exportUrl` is plain `http://`, since the token then travels unencrypted. Set the token with the `OTHERLODE_AUTH_TOKEN` environment variable, because a `-javaagent` argument is visible to every user on the host through `ps`. See [configuration options](configuration).

The agent reads no response body. A `2xx` status confirms the payload. Each request times out after 10 seconds, and so does the connection attempt.

## When payloads are sent

The agent flushes on a fixed interval, `flushIntervalSeconds`, 60 seconds by default and between 1 and 86400. The first flush comes at a random offset between 0 and one interval after startup, so a fleet that starts together does not flush on the same tick. The flushes after it keep the fixed rate from that offset.

Each flush sends the delta batch and, if something is waiting, the manifest, at the same time. A flush that finds a dependency whose counts were just delivered sends one more manifest for it, and a flush that completes the dependency listing may send one more, empty, manifest that says so.

On a graceful JVM exit a shutdown hook runs one last flush with `final_flush` set on its delta batches. It has a budget of 10 seconds in total, shared with any flush still running, and it skips the static baseline retry. A flush still working against an unreachable collector can use the whole budget, and then no final flush runs. `kill -9`, an out-of-memory kill and a crash send no final flush.

### Chunk caps

A payload that would be larger than its cap is split into several requests, sent in order.

| Payload | Cap per request |
|---|---|
| Delta batch | 20,000 entries (probe, endpoint and dependency deltas together) |
| Probe manifest | 5,000 entries, where call edges, referenced classes and branch sites inside a probe each count as an entry |
| Static baseline | About 20,000 entries, where a declared class weighs its methods plus their call edges, branch sites and references. A class is never split, so a heavier class gets a chunk of its own |

## Payloads

Every payload carries the same resource attributes.

### Resource attributes

| Field | Content |
|---|---|
| `service_name` | The service name from your configuration, OpenTelemetry settings or detection |
| `service_namespace` | Optional. Only if you or OpenTelemetry settings set it |
| `service_version` | Optional |
| `service_instance_id` | `UUID` made at startup, unless you set `serviceInstanceId` |
| `environment` | Optional. `test` for a test run with no other source |
| `run_id` | A random `UUID` made once per process. It differs on every start, even under a pinned `service_instance_id` |
| `test_run` | True when you started the agent with `testRun` |
| `agent_version` | The agent jar's version, empty when unknown |
| `fields_stripped` | Never set by the agent. A collector sets it when it removed a field before forwarding |

The agent adds no host name, address, user name, environment variable or system property. Resource attributes come from your configuration, from OpenTelemetry's `service.*` and `deployment.environment*` settings (see [configuration options](configuration)), and from service name detection when you set none.

The run id lets a collector keep one process's data apart from the next when an instance id is pinned. A consumer needs it because class ids and every total only mean something within one run.

### Delta batch

A delta batch carries the resource attributes plus these lists. Each entry is a number pair or triple, with no names.

| Field | Content |
|---|---|
| `deltas` | For each probe whose count changed: `class_id`, `probe_index`, `kind` (method, branch or optional argument), `first_seen_at` and `hits_total` |
| `endpoint_deltas` | For each endpoint whose count changed: `endpoint_id`, `first_seen_at`, `hits_total` |
| `dependency_deltas` | For each dependency: `dependency_id`, `first_loaded_at`, `loaded_classes_total` |
| `final_flush` | True only on the shutdown flush |

`hits_total` is the cumulative count since the process started, not the amount since the last flush. A collector merges it with `max()`, so a batch that arrives twice or out of order changes nothing. `first_seen_at` is the time of the flush that first saw a non-zero count, so it is accurate to one interval.

The agent sends the batch even when no count changed. An empty batch is a heartbeat: it tells a collector the instance is alive and idle, not crashed or cut off.

The ids refer to the manifest of the same run. They mean nothing without it.

### Probe manifest

The manifest says what each id in a delta batch names. It carries the resource attributes plus the lists below. Class entries go out once. After a manifest carrying a class is confirmed, the agent drops that class's names and keeps only each probe's kind, so a collector that loses an instance's manifest gets it back when the instance restarts.

| Field | Content |
|---|---|
| `probes` | One per probe: `class_id`, `probe_index`, `kind`, class name, method name, method descriptor, source line, parameter names, generic signature, and flags for inline, static, lambda body and extension receiver |
| `probes[].branch_sites` | For a branch probe: the site's line, condition, outcomes with their roles, guarded line ranges (source file name plus first and last line) and case labels. See [methods and branches](methods-and-branches) |
| `probes[].calls`, `probes[].referenced_classes` | Call edges to other classes in scope, and names of classes outside it that the method refers to |
| `class_locations` | Per class: super class name, interface names, source file name, body kind, and source name |
| `class_references` | Per class: names of classes it refers to outside any probed method |
| `skipped_classes` | Class name, the reason as free text (usually an exception message), and the time |
| `failed_classes` | Name of a class that was woven but never loaded, and the time |
| `unreported_classes` | Name of a class the agent found loaded that no transformer was offered, and the time |
| `endpoints` | Per endpoint: `endpoint_id`, verb, route template, the framework's own spelling of the template, framework name, how it was discovered, and the handler class, method and descriptor when known |
| `disabled_endpoint_modules` | Module name, the reason as free text, the time, and a kind naming why: a linkage error, an advice failure, a transform failure, a route walk failure, or a hook that matched no method |
| `dependencies` | Per dependency: `dependency_id`, identities (`group_id`, `artifact_id`, `version`), how the identity was read, the location, how it was discovered, and class count |
| `external_classes` | Name of a class outside scope that your code refers to, the `dependency_id` that provides it, and whether nothing provides it |
| `references_recorded`, `dependencies_listed` | Flags a collector uses to know when references and the startup dependency listing are complete |

#### Condition text and string literals

A branch site's condition is source-like text rebuilt from bytecode, in parts. A part is code, a placeholder, or a string literal. A string literal part holds the string constant from your class file as the compiler stored it, such as `"ENABLE_LEGACY_DISCOUNT"` in `if (System.getenv("ENABLE_LEGACY_DISCOUNT") != null)`. It also holds the labels of a `when` or `switch` on a string. Nothing is read at run time: the text is a constant in your bytecode, never a value your program computed or received. A constant that holds a secret, such as a hard-coded password or a key compared against input, is sent as it is. A collector can replace string literal parts, because they are marked as such.

#### Names and paths

Class, method and parameter names, source file names and line numbers come from your class files. A class compiled without debug information sends no lines or parameter names.

A dependency's `location` is the jar's path or its entry inside a fat jar, such as `~/.m2/repository/org/x/x-1.0.jar` or `BOOT-INF/lib/x-1.0.jar`. A path that starts with the JVM's `user.home` folder is written with `~` in its place, so the payload does not name the account that ran the agent. Only a whole leading folder matches: `/home/al` does not rewrite `/home/alice/x.jar`. A path under another user's home folder is sent as it is.

#### Endpoint routes

An endpoint carries its route template, such as `GET /orders/{id}`, not the URL of any request. The agent reads the pattern the framework matched. It never reads a path, query string, header, parameter or body from a request. An endpoint that no request has reached still appears if the framework registered it. See [endpoints](endpoints).

### Static baseline chunk

The agent sends a static baseline only when you set `staticBaselineEnabled`. A background scan lists the classes on the classpath under your `includePackages` and sends them once the scan ends. Each chunk carries the resource attributes, `scanned_at`, `chunk_index` and `chunk_count`.

| Field | Content |
|---|---|
| `declared_classes` | Per class: name, super class and interface names, source file name, body kind, and per method the name, descriptor, parameter names, generic signature, call edges, branch sites (with conditions) and referenced classes |
| `unreadable_classes` | Name of a class file the scanner could not read, and the reason |
| `unprobed_classes` | Name of a class with no method to count, and the reason |
| `external_classes` | The same mapping as in the manifest |

A collector can use the baseline only when it holds every chunk of a scan, which `chunk_count` tells it. See [classes](classes).

## What the agent never sends

- The path, query string, headers, parameters or body of a request, or a response.
- Argument values, return values, field values, or exception messages from your code.
- Environment variables or system properties.
- Host names, addresses, or user names, apart from a user name inside a jar path that is not under `user.home`.
- Source code. Source file names and line numbers go out, and so does condition text rebuilt from bytecode, but no source file content.

A probe only increments a counter in an array. It reads no value.

The free text in `skipped_classes`, `unreadable_classes`, `unprobed_classes` and `disabled_endpoint_modules` is a reason from the JVM or the agent, such as a linkage error message. It names classes and does not hold application data.

## When the collector is down or refuses a payload

The agent keeps no queue of payloads. After a failed send, the state that the send would have advanced stays where it was, and the next flush computes the payload again from live counts. The memory the agent uses for this is bounded by the size of your code, not by traffic or by how long the collector is away.

### Retries inside one send

One send makes up to 5 attempts, waiting 200 ms after the first failure and doubling the wait up to a cap of 30 seconds. It retries only when a retry can help:

- A connection error or a timeout.
- A `5xx` status.
- A `408` or `429` status.

Every other status, including a `401`, `403`, `404` and a redirect, fails the send at once, because the same bytes would meet the same answer. Only a `2xx` confirms.

### After a failed send

- **Delta batch.** The counts are not marked as delivered. The next flush reports the live cumulative count again, which is how a lost acknowledgement or a late batch heals. A flush stops at its first failed delta request, and the requests it did confirm stay confirmed.
- **Manifest.** A chunk the collector did not confirm stays pending and is built again on the next flush. The agent builds, sends and confirms one chunk at a time and stops at the first failure.
- **Static baseline.** Chunks go out in order and the first failure stops the send. The chunks left are sent again, one per flush, after a flush whose own sends the collector confirmed. A `400`, `413` or `422` answer means the collector refused the content, and the agent drops every chunk left and logs a warning. Any other failure, such as a `401` while a token rotates, keeps the chunks. The scan is never repeated in a process.
- **Resending a delivered payload.** If the collector processed a request but the agent never saw the answer, the agent sends the same payload again. Cumulative counts and chunk indexes make the repeat harmless.

Each failure logs a warning that names the payload and says the agent will retry on the next flush. A flush that fails never stops the schedule.

### What a crash loses

Counts and manifest state live only in the process. A crash during an outage loses what the collector had not confirmed, which is at most the hits since the last confirmed flush. A graceful exit tries to send that remainder, as described above. History the collector already confirmed is not affected.

## Where the schema lives

The wire schema is one file, `src/main/proto/otherlode/v1/otherlode.proto`, package `otherlode.v1`. Its comments are the authoritative description of each field. The schema is published to the Buf Schema Registry as `buf.build/otherlode/otherlode`, and the collector and server build their bindings from it.
