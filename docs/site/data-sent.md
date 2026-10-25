---
title: What the agent sends
description: The requests the agent makes, what each payload contains, when it is sent, what it never contains, and what happens when the collector is down.
order: 130
---

## Summary

The agent pushes four kinds of payload to the collector you set with `exportUrl`. Every payload is a protobuf message, and a payload names code (classes, methods, source files, lines, conditions, routes, jars) and counts. It carries no request data and no argument or return value.

The agent has no redaction setting. It sends what this page lists. A collector with redaction switched on replaces the text of [string literal parts](#condition-text-and-string-literals) with `…` before it forwards a payload, either every such part or those that match a pattern you set. It keeps every field it knows. It drops any field it does not know, which happens when the agent is newer than the collector, and marks that payload with `fields_stripped`. A deployment that needs redaction sends through a collector with redaction switched on. An agent that posts straight to a backend sends everything in clear.

## Requests

Every request is a `POST` to a path under `exportUrl`. The agent drops a trailing slash from `exportUrl` and refuses a query string or fragment, so the paths below always sit directly under the URL you set. The default `exportUrl` is `http://localhost:4319`.

| Path under `exportUrl` | Payload | Sent |
|---|---|---|
| `/v1/otherlode/deltas` | Delta batch | Every flush, even when empty |
| `/v1/otherlode/manifest` | Probe manifest | On a flush that has something not yet delivered |
| `/v1/otherlode/static-baseline` | Static baseline chunk | Once per process, when `staticBaselineEnabled` is on |
| `/v1/otherlode/class-listing` | Class listing chunk | Once per process, when `classListingEnabled` is on, which is the default |

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

On a graceful JVM exit a shutdown hook runs one last flush. It ends with an empty delta batch that carries `final_flush`. It has a budget of 10 seconds in total, shared with any flush still running, and it skips the static baseline and class listing retries. A flush still working against an unreachable collector can use the whole budget, and then no final flush runs. `kill -9`, an out-of-memory kill and a crash send no final flush.

### Chunk caps

A payload that would be larger than its cap is split into several requests, sent in order. A request closes when the next entry would take it past any of three bounds: its entry cap, 8 MiB of encoded bytes, or 90% of the collector's element limit for its kind. An element is every embedded message and every element of a repeated field, at every depth, in the protobuf encoding. The resource counts as one. The agent measures bytes and elements on the encoding it sends.

| Payload | Entry cap | Element bound |
|---|---|---|
| Delta batch | 20,000 entries (probe, endpoint and dependency deltas together) | 450,000 |
| Probe manifest | 5,000 entries, where call edges, referenced classes and branch sites inside a probe each count as an entry | 90,000 |
| Static baseline | About 20,000 entries, where a declared class weighs its methods plus their call edges, branch sites and references | 180,000 |
| Class listing | 20,000 class names | 90,000 |

A class is never split across requests, so a class can break a bound on its own. What the agent does depends on the payload:

- **Manifest.** The class goes out as a skipped class with the reason `too large to report: its manifest entries exceed the collector's limits`, in place of its probes. The server counts it as loaded and claims nothing about its methods. The agent leaves the class's probe deltas out of every delta batch, and logs a `WARNING` that names the class. The classes behind it go out as usual.
- **Static baseline.** The agent sends no chunk of the scan and logs one `WARNING` that names the class and the bound. A scan without the class would read as complete, and the server would then drop the class's call edges and creations from the baseline. The run has no complete scan, so the server makes no never-loaded claim for it.
- **Class listing and delta batch.** A class name is at most 65,535 bytes, and a class never has more deltas than probe locations in its manifest, so neither can break a bound on its own.

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
| `flush_interval_seconds` | The value of `flushIntervalSeconds`, in whole seconds. The server reads it to tell a run that stopped without its final flush from one that is quiet between flushes |
| `fields_stripped` | Never set by the agent. A collector sets it only while its redaction is switched on, when it dropped a field it does not know before forwarding |

The agent adds no host name, address, user name, environment variable or system property. Resource attributes come from your configuration, from OpenTelemetry's `service.*` and `deployment.environment*` settings (see [configuration options](configuration)), and from service name detection when you set none.

The run id lets the server keep one process's data apart from the next when an instance id is pinned. A consumer needs it because class ids and every total only mean something within one run.

### Delta batch

A delta batch carries the resource attributes plus these lists. Each entry is a number pair or triple, with no names.

| Field | Content |
|---|---|
| `deltas` | For each probe whose count changed: `class_id`, `probe_index`, `kind` (method, branch, optional argument, initializer returned or initializer threw), `first_seen_at` and `hits_total` |
| `endpoint_deltas` | For each endpoint whose count changed: `endpoint_id`, `first_seen_at`, `hits_total` |
| `dependency_deltas` | For each dependency: `dependency_id`, `first_loaded_at`, `loaded_classes_total` |
| `final_flush` | True only on the last, empty delta batch of the shutdown flush |
| `payload_sequence`, `counts_pending_since` | Where the run stands in delivering its counts; see [Pending counts](#pending-counts) |

`hits_total` is the cumulative count since the process started, not the amount since the last flush. The server merges it with `max()`, so a batch that arrives twice or out of order changes nothing. `first_seen_at` is the time of the flush that first saw a non-zero count, so it is accurate to one interval.

The agent sends the batch even when no count changed. An empty batch is a heartbeat: it tells the server the instance is alive and idle, not crashed or cut off.

The ids refer to the manifest of the same run. They mean nothing without it.

### Pending counts

Every delta batch and manifest carries two numbers that tell the server whether the run's counts are behind. Static baseline and class listing chunks carry neither.

- `payload_sequence` numbers the run's delta batches and manifests from 1, one higher each time. The payload with the highest number is the run's latest word, whatever order the payloads arrived in. A gap in the numbers means nothing.
- `counts_pending_since` is 0 when, as of the moment the payload was stamped, no count the run sent has been refused or gone unanswered. Otherwise it is the time, in milliseconds since the epoch, of the flush in which that first happened.

While the latest payload of a run carries a value other than 0, some of its hits have not reached the collector, so its zero hit totals are not evidence that code never ran. Nor are they before any delta batch from the run has arrived. The manifest and the delta batches go out side by side, so a collector can accept one and refuse the other; the manifest then arrives at zero hits for code that did run. One gap remains: payloads stamped in the flush where refusals begin, before the first refusal, carry 0. If the collector accepts nothing after that, up to one flush interval of hits is missing while the run's newest payload says 0, and the run reads like one that stopped without a final flush.

### Probe manifest

The manifest says what each id in a delta batch names. It carries the resource attributes plus the lists below. Class entries go out once. After a manifest carrying a class is confirmed, the agent drops that class's names and keeps only each probe's kind, so if the manifest is lost after that, the server gets it back only when the instance restarts.

| Field | Content |
|---|---|
| `probes` | One per probe: `class_id`, `probe_index`, `kind`, class name, method name, method descriptor, source line, parameter names, generic signature, and flags for inline, static, lambda body and extension receiver. A branch probe also carries its `branch_key` |
| `probes[].branch_sites` | For a branch probe: the site's `site_key`, line, condition, outcomes with their roles, guarded line ranges (source file name plus first and last line), case keys and case labels. `string_hash_code_switch` marks a switch on `String.hashCode()`. Each case key of such a switch is the hash code of a string, often one of your literals, so a collector can clear them. See [methods and branches](methods-and-branches) |
| `probes[].calls`, `probes[].referenced_classes` | Call edges to other classes in scope, and names of classes outside it that the method refers to |
| `class_locations` | Per class: super class name, interface names, source file name, body kind, and source name |
| `class_references` | Per class: names of classes it refers to outside any probed method |
| `skipped_classes` | Class name, the reason as free text (usually an exception message), and the time |
| `failed_classes` | Name of a class that was woven but never loaded, and the time |
| `classes_failed_to_initialize` | Name of a class the JVM defined that can never run, the reason, and the time the agent first saw it. The reason is `STATIC_INITIALIZER_THREW` when its static initializer threw, or `VERIFIER_REJECTED` when the JVM's verifier rejected it. A class is listed once per reason. The two probes behind the first reason, kinds initializer returned and initializer threw, count how often the static initializer returned and how often it threw |
| `unplaced_verification_failures` | How many `VerifyError` messages were in no format the agent reads, plus the messages the agent had no room to keep. Every manifest carries the run's total |
| `verifier_rejections_watched` | True when the agent's hook on `VerifyError` is installed. Every manifest of a run agrees |
| `unreported_classes` | Name of a class the agent found loaded that no transformer was offered, and the time |
| `endpoints` | Per endpoint: `endpoint_id`, verb, route template, the framework's own spelling of the template, framework name, how it was discovered, whether the template is a path, a regex or a servlet pattern, and the handler class, method and descriptor when known |
| `disabled_endpoint_modules` | Module name, the reason (the exception's class name when the module failed on an exception), the time, and a kind naming why: a linkage error, an advice failure, a transform failure, a route walk failure, or a hook that matched no method |
| `framework_markers` | Per marker: its id, the class set that matched as fully qualified names, and the time of the sweep that saw it. Each is delivered once per run. See [frameworks with no module](endpoints#frameworks-with-no-module) |
| `dependencies` | Per dependency: `dependency_id`, identities (`group_id`, `artifact_id`, `version`), how the identity was read, the location, how it was discovered, and class count |
| `external_classes` | Name of a class outside scope that your code refers to, the `dependency_id` that provides it, and whether nothing provides it |
| `references_recorded`, `dependencies_listed` | Flags the server uses to know when references and the startup dependency listing are complete |
| `payload_sequence`, `counts_pending_since` | Where the run stands in delivering its counts; see [Pending counts](#pending-counts) |

#### Branch and site keys

A `branch_key` names one outcome of a branch site across builds and instances, and a `site_key` names the site. Each key is 32 lowercase hex characters, compared only for equality. The agent derives a branch key from the class, the method name and descriptor, the origin class, the condition fingerprint and the outcome. A site key comes from the same inputs without the outcome. The origin class is the class of the inline function a branch was copied from, and empty for the class's own code. The condition fingerprint is the instructions that compute the condition. A key stays the same across builds while its inputs do not change. A key is absent when the agent cannot name an outcome safely. See [methods and branches](methods-and-branches) for what changes a key and when it is absent.

#### Condition text and string literals

A branch site's condition is source-like text rebuilt from bytecode, in parts. A part is code, a placeholder, or a string literal. A string literal part holds the string constant from your class file as the compiler stored it, such as `"ENABLE_LEGACY_DISCOUNT"` in `if (System.getenv("ENABLE_LEGACY_DISCOUNT") != null)`. It also holds the labels of a `when` or `switch` on a string. Nothing is read at run time: the text is a constant in your bytecode, never a value your program computed or received. A constant that holds a secret, such as a hard-coded password or a key compared against input, is sent as it is. A collector can replace string literal parts, because they are marked as such.

The condition fingerprint includes the string constants in the condition, so the [keys](#branch-and-site-keys) of a condition that holds a string literal depend on its text. A branch key for a case of a `when` or `switch` on a string also covers the case's label. <!-- TODO: keys -->

#### Names and paths

Class, method and parameter names, source file names and line numbers come from your class files. A class compiled without debug information sends no lines or parameter names.

A dependency's `location` is the jar's path or its entry inside a fat jar, such as `~/.m2/repository/org/x/x-1.0.jar` or `BOOT-INF/lib/x-1.0.jar`. A path that starts with the JVM's `user.home` folder is written with `~` in its place, so the payload does not name the account that ran the agent. Only a whole leading folder matches: `/home/al` does not rewrite `/home/alice/x.jar`. When the home folder is reached through a symbolic link, a path under its real path, with every link followed, is written with `~` too. A path under another user's home folder is sent as it is.

#### Endpoint routes

An endpoint carries its route template, such as `GET /orders/{id}`, not the URL of any request. The agent reads the pattern the framework matched. It never reads a path, query string, header, parameter or body from a request. An endpoint that no request has reached still appears if the framework registered it. See [endpoints](endpoints).

### Static baseline chunk

The agent sends a static baseline only when you set `staticBaselineEnabled`. A background scan lists the classes on the classpath under your `includePackages` and sends them once the scan ends. Each chunk carries the resource attributes, `scanned_at`, `chunk_index` and `chunk_count`.

| Field | Content |
|---|---|
| `declared_classes` | Per class: name, super class and interface names, source file name, body kind, and per method the name, descriptor, parameter names, generic signature, call edges, branch sites (with conditions and site keys) and referenced classes |
| `unreadable_classes` | Name of a class file the scanner could not read, and the reason |
| `unprobed_classes` | Name of a class with no method to count, and the reason |
| `external_classes` | The same mapping as in the manifest |
| `include_rules` | The include and exclude rules the scan applied, as the agent parsed them: `include_packages` and `exclude_packages` |

The server uses the baseline only when it holds every chunk of a scan, which `chunk_count` tells it. A scan that holds a class too large for one chunk is not sent. See [classes](classes).

### Class listing chunk

The agent sends a class listing unless you set `classListingEnabled=false`. A background thread lists the names of the classes the classpath holds under your `includePackages` and sends them once, without reading a class file. It also looks inside the jars nested in a Spring Boot executable jar or WAR. Each chunk carries the resource attributes, the rules, `listed_at`, `chunk_index` and `chunk_count`.

| Field | Content |
|---|---|
| `class_names` | The dotted names of the listed classes, distinct across the listing and sent in ascending order. A chunk holds at most 20,000 |
| `include_rules` | The include and exclude rules the listing applied, as the agent parsed them. A prefix covers a name that equals it, or continues it with `.` or `$`. The server uses them to tell a class outside the rules from a class the build lacks |
| `listed_at` | When the listing was made, in milliseconds since the epoch. Every chunk of one listing carries the same value |
| `chunk_index`, `chunk_count` | Which part this chunk is, counting from 0, and how many parts the listing has |

A listing that matches no class is one chunk with no names. A process sends no listing when a directory, jar or nested jar it reads cannot be read, because the server reads a class's absence from a complete listing as proof that the build lacks it. The server uses a listing only when it holds every chunk. See [classes](classes#the-class-listing).

## What the agent never sends

- The path, query string, headers, parameters or body of a request, or a response.
- Argument values, return values, field values, or exception messages from your code.
- Environment variables or system properties.
- Host names, addresses, or user names, apart from a user name inside a jar path that is not under `user.home` or its real path.
- Source code. Source file names and line numbers go out, and so does condition text rebuilt from bytecode, but no source file content.

A probe only increments a counter in an array. It reads no value.

The free text in `skipped_classes`, `unreadable_classes`, `unprobed_classes` and `disabled_endpoint_modules` is a reason from the JVM or the agent, such as a linkage error message. For `disabled_endpoint_modules`, when the module failed on an exception other than a linkage error, it is the exception's class name, never its message. It names classes and does not hold application data.

## When the collector is down or refuses a payload

The agent keeps no queue of payloads. After a failed send, the state that the send would have advanced stays where it was, and the next flush computes the payload again from live counts. The memory the agent uses for this is bounded by the size of your code, not by traffic or by how long the collector is away.

### Retries inside one send

One send makes up to 5 attempts, waiting 200 ms after the first failure and doubling the wait up to a cap of 30 seconds. It retries only when a retry can help:

- A connection error or a timeout.
- A `5xx` status.
- A `408` or `429` status.

Every other status, including a `401`, `403`, `404` and a redirect, fails the send at once, because the same bytes would meet the same answer. Only a `2xx` confirms.

### After a failed send

- **Delta batch.** The counts are not marked as delivered. The next flush reports the live cumulative count again, which is how a lost acknowledgement or a late batch heals. A refused request, meaning a `4xx` status the agent does not retry, does not stop the flush: the later delta requests still go, and the refused one is built again on the next flush. Any other failure stops the flush's delta requests at that one; the manifest, sent alongside, is not affected. The requests it did confirm stay confirmed either way. Counts a collector has not confirmed are pending: the next flush leads with an empty delta batch that says so, sent on its own so the news arrives even when the batch with the counts is refused for its size. A flush that then confirms every batch with counts ends with another empty batch saying nothing is pending.
- **Manifest.** A chunk the collector did not confirm stays pending and is built again on the next flush. The agent builds, sends and confirms one chunk at a time and stops at the first failure. A disabled endpoint module goes first, on a manifest of its own, before any class chunk, since its record is what tells the server the endpoints sent earlier are no longer counted.
- **Static baseline.** Chunks go out in order and the first failure stops the send. The chunks left are sent again, one per flush, after a flush whose own sends the collector confirmed. A `400` or `422` answer means the collector refused the content, and the agent drops every chunk left and logs a warning. Any other failure keeps the chunks, such as a `401` while a token rotates or a `413`, since a proxy's size limit can be raised while the process runs. The scan is never repeated in a process.
- **Class listing.** Sent and resent as the static baseline is, with the same rules for each status. A `400` or `422` answer drops the listing for the process. Any other failure keeps the chunks, including a `404` from a collector that does not serve the route yet.
- **Resending a delivered payload.** If the collector processed a request but the agent never saw the answer, the agent sends the same payload again. Cumulative counts and chunk indexes make the repeat harmless.

Each failure logs a warning that names the payload and says the agent will retry on the next flush. A flush that fails never stops the schedule.

### What a crash loses

Counts and manifest state live only in the process. A crash during an outage loses what the collector had not confirmed, which is at most the hits since the last confirmed flush. A graceful exit tries to send that remainder, as described above. History the collector already confirmed is not affected.

## Where the schema lives

The wire schema is one file, `src/main/proto/otherlode/v1/otherlode.proto`, package `otherlode.v1`. Its comments are the authoritative description of each field. The schema is published to the Buf Schema Registry as `buf.build/otherlode/otherlode`, and the collector and server build their bindings from it.
