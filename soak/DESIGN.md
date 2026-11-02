# Soak test

A week-long run of the agent, the collector and the server against two test services whose every
finding is planted and written down. The harness drives scheduled traffic and disruptions and checks
Otherlode's read API against the written answers every six hours, and reads every finding every 5
seconds for 150 seconds after any start, restart, kill, upgrade, collector start or server restart. It runs on one VM. It needs
Otherlode's server, which is not public.

## Layout

- `soak/apps/`: a Gradle build of its own, not part of the agent's build. Two applications and the
  truth file.
- `soak/harness/`: Python 3 scripts, standard library only. The stack, the supervisor, the traffic,
  the sampler and the checker.
- `soak/truth.json`: the planted answers. The apps' authors write it; the checker reads it.

## The services

Every instance but the three controls runs with the agent as `-javaagent`, `staticBaselineEnabled=true`
(`shop-3` runs with `false`), `classListingEnabled=true` (the default), and `flushIntervalSeconds` 60
(10 in a smoke run; `events-crash` flushes every 600 at every scale). Each sends to the collector on the
same VM.

| Instance | App | `serviceName` | `environment` | `serviceVersion` | Port | Agent |
|---|---|---|---|---|---|---|
| `shop-1` | soak-shop | `soak-shop` | `prod` | `1.0.0` | 8081 | yes |
| `shop-2` | soak-shop | `soak-shop` | `prod` | `1.0.0` | 8082 | yes |
| `shop-3` | soak-shop | `soak-shop` | `nobaseline` | `1.0.0` | 8083 | yes, with `staticBaselineEnabled=false`. Gets shop's traffic routes at a lower rate. Compared with `shop-control`. It has an environment of its own: a run that records references with no complete scan makes every dependency of its scope `no-live-reference` (server `dependencies.go`, `split`), which would change `soak-shop/prod`'s planted answers all week. `truth.json` names no answers for it, so checks 1 and 2 do not judge it |
| `shop-control` | soak-shop | none | none | none | 8089 | no, the memory baseline |
| `events-prod` | soak-events | `soak-events` | `prod` | `1.0.0`, then `2.0.0` | 8091 | yes |
| `events-staging` | soak-events | `soak-events` | `staging` | `1.0.0` | 8092 | yes |
| `events-crash` | soak-events | `soak-events` | `prod` | `1.0.0` | 8093 | yes, with `flushIntervalSeconds=600` at every scale, so its first flush, at a random point in that interval, rarely comes before the kill. Started and killed by the schedule, never kept up. No traffic. Compared with `events-control` |
| `events-control` | soak-events | none | none | none | 8099 | no, the memory baseline for `events-prod`. It gets `events-prod`'s traffic and its upgrade |
| `events-staging-control` | soak-events | none | none | none | 8098 | no, the memory baseline for `events-staging`. It gets `events-staging`'s traffic and runs 1.0.0 all week |
| `shop-test` | soak-shop's `SoakTestDriver` main | `soak-shop` | unset, so `test` | `1.0.0` | none | yes, with `testRun=true` and `serviceInstanceId=soak-shop-tests`, once a day |

`soak-shop` uses `includePackages=dev.soaktest.shop`, and its production instances add
`excludePackages=dev.soaktest.shop.testdriver`, so their baselines never declare the test driver.
`soak-events` uses `includePackages=dev.soaktest.events`. The agent never probes a class under
`dev.otherlode`, so the apps live outside it.

### soak-shop

Spring Boot 4.1.1 with Spring MVC, Kotlin 2.2.21 and Java 17 sources together, built as one fat
jar. It also registers one plain servlet with a `ServletRegistrationBean`.

Endpoints:

| Route | Traffic | Planted answer |
|---|---|---|
| `GET /orders/{id}` | 5 a second per instance | Live. Its service code holds a branch whose true side never runs (`id < 0`) and a `when` on a tier whose `PLATINUM` case never runs |
| `POST /orders` | one every 10 seconds per instance | Live. Calls `OrderSearch.search(q, limit = 5)`. A fallback lambda in it never runs |
| `GET /reports/monthly` | one every 6 hours | Live |
| `GET /reports/yearly` | once, 10 minutes after the start | Live, then a stale hit at `days=1` from 26 hours after the start |
| `GET /once` | once, 96 hours after the start | Never called until then, live after |
| `GET /broken` | once, 5 minutes after the start | Its handler touches `BrokenConfig`, whose static initialiser throws. The request answers 500 |
| `DELETE /orders/{id}` | never | Never called |
| `GET /admin/rebuild` | never | Never called. Its handler is the only code that reaches `LegacyCsvExporter` |
| `GET /health-legacy` (plain servlet) | once a minute | Live. The servlet declares `doPost`, which is never called |

Classes and methods:

- `Pricing.applyLegacyDiscount`: never hit, in a class that ran.
- `LegacyCsvExporter`: never loaded. Its methods call commons-csv.
- `OldPromotions`: never loaded, referenced by nothing.
- `TaxTables` (Java, with a static initialiser): loaded through a class literal at startup and
  never initialised.
- `AuditRecord` (Java): a static method runs on every request, so it is loaded and initialised. Its
  constructor and instance methods never run, so it is never instantiated.
- `BrokenConfig`: failed to initialise.
- `OrderSearch.search(q: String, limit: Int = 10, sort: String = "asc")`, in a final class that no
  Spring plugin opens: `limit` is always supplied and `sort` never supplied.
- `OrderView`, a Kotlin data class whose `copy` and `componentN` never run: they are generated and
  must appear in no finding.
- An unreached cluster rooted at `AdminController.rebuild`, holding `LegacyCsvExporter`'s methods.
- `ShutdownHook`, a bean with a `@PreDestroy` method `close` that sleeps 2 seconds and then calls
  `ShutdownAudit.record()`. `ShutdownAudit` is a Kotlin `object` in `dev.soaktest.shop` whose `record`
  nothing else calls; `Startup` calls its `armed()`, so the class loads and runs. Both methods run at
  every graceful stop, so they are never hit until `shop-1`'s first graceful restart at T+12h, and live
  from then on. Before it, `ShutdownHook.close` roots a cluster that holds `ShutdownAudit.record`. The
  2 second sleep puts the hit after the moment a shutdown flush could have been taken.

Dependencies:

- commons-csv: unreached, referenced only from `LegacyCsvExporter`.
- commons-text: on the classpath, never referenced and never loaded: unloaded.
- guava: used live, from the `GET /orders/{id}` path.

### soak-events

Ktor 3.5.2 on CIO, Kotlin 2.2.21, kotlinx.serialization. Version 2.0.0 is a second jar built from
the same sources with a build flag.

| Route | Traffic | Planted answer |
|---|---|---|
| `GET /events` | 2 a second | Live. A suspend function on its path holds a branch side that never runs |
| `POST /events` | one every 30 minutes | Live |
| `GET /events/archive` | prod never, staging every 10 minutes | Never called in prod, live in staging |
| `GET /events/{id}/replay` (1.0.0 only) | never | Never called. Its handler is the only code that reaches `ReplayEngine` |
| `GET /events/stats` (2.0.0 only) | 2 a second from the upgrade | Live in prod after the upgrade |

- `Event` is `@Serializable`. Its generated serializer must appear in no finding.
- `LegacyDigest` (2.0.0 only): refers to commons-codec's `Hex`, and nothing loads it. 2.0.0's `main`
  loads `Hex` by name, once, as `Startup` loads commons-csv in the shop. The 2.0.0 jar names
  `lib/commons-codec-1.19.0.jar` in its manifest `Class-Path`, so the agent lists the codec jar apart
  from the application. No 1.0.0 jar refers to it. From the upgrade, soak-events/prod reads commons-codec
  as `unreached` with `LegacyDigest` as its site, never `unreferenced` or `unloaded`, and reads
  `LegacyDigest` as never loaded.
- `ReplayEngine` (1.0.0 only): never loaded. After the upgrade, with the 1.0.0 run ended cleanly, it
  and the replay endpoint are on prod's Removed list and in no prod finding. Staging keeps them.

## Schedule

Times are from the start, T0. A smoke run divides every time by 60, so an hour takes a minute. The gaps given in real seconds or
minutes (the crash cycles, the refusal window, the window reads) are the same at every scale.

| When | What |
|---|---|
| T0 | Start the stack and every instance but `shop-test` |
| T+5m | `GET /broken` once on `shop-1` |
| T+10m | `GET /reports/yearly` once on `shop-1` |
| every 12h | Graceful restart of `shop-1` (SIGTERM, wait, start) |
| T+30h | `kill -9` of `shop-2`, start it again 2 minutes later |
| T+20h, then 4, 8 and 12 real minutes later | `events-crash`: start, wait until it is ready, wait 5 real seconds, `kill -9`. The fourth start runs with `staticBaselineEnabled=false`. A start rarely reaches its first flush |
| T+40h | Stop the collector for 60 minutes |
| T+50h | Restart the server |
| T+61h | Restart the collector with a manifest element limit that a restarted `shop-1` exceeds: 20 elements. The collector has no variable for its element limits, so the stack runs a build of it with a lower `MaxManifestElements`. `shop-1` restarts gracefully one fifth of the way into the window. 10 real minutes later (2 in a smoke run) the collector restarts with the normal limit. It starts an hour after `shop-1`'s 12 hour restart at T+60h, so no run of `shop-1` lives its whole life inside the window. The low limit is skipped when it falls due after the window's end, and the normal limit never runs before it |
| T+72h | Upgrade `events-prod` and `events-control` to 2.0.0: SIGTERM, start the 2.0.0 jar |
| T+96h | `GET /once` once on `shop-2` |
| T+1h, then daily | `shop-test` runs `SoakTestDriver`, a main in the shop jar under `dev.soaktest.shop.testdriver`, which calls `Pricing.applyLegacyDiscount` and exits. It must change no finding, and the cluster rooted at `applyLegacyDiscount` must read as called only by tests |
| after each start, restart, kill, upgrade, collector start and server restart | The window reads: every finding, live, every 5 real seconds for 150 real seconds, and the report and the snapshot-served routes every real minute for 20 real minutes |
| every 6h, and at the end | The checker |
| T+168h | The final check and the report |

## Checks

The checker reads the API with the read key and writes one report per run. Each check passes or
fails with the evidence.

1. **Planted answers.** Every entry in `truth.json` whose time has come holds.
2. **No false claim.** No class, method, branch outcome, endpoint, parameter or dependency under
   the include rules appears in a finding unless `truth.json` lists it there. A method in
   `live_methods` is on no never-hit finding from its time.
3. **Counts.** For each live endpoint, Otherlode's `calls_total` equals the 2xx responses the
   traffic generator recorded, give or take one flush interval of requests. The collector outage
   loses nothing. `shop-1` and `shop-2` count together, and the collector restarts of the
   refusal window add one flush interval of requests to the tolerance. A planned collector stop,
   collector start, collector limit change or server restart whose command fails
   or times out fails this check, and so does a collector stop skipped because it fell due more
   than 30 minutes late.
4. **Runs.** Every graceful stop reads as ended cleanly, and the `kill -9` does not. The runs of
   `events-crash` are not asserted, since each is killed. A graceful stop inside the
   refusal window is not asserted, as for the collector outage.
5. **Memory.** After a 30-minute warm-up, the resident memory of each agent process grows no faster
   than that of its control at the same process age, plus 10%. `shop-1`, `shop-2` and `shop-3` are
   compared with `shop-control` (which gets `shop-3` a higher traffic rate than it has), `events-prod` and `events-crash`
   with `events-control`, and `events-staging` with `events-staging-control`. A span of two or more samples at ages the control does not cover is
   listed as not compared.
6. **Logs.** No ERROR line from any agent, the collector or the server, and no WARNING outside an
   allowlist that names the disruption that caused it. One ERROR is allowed: the collector answers
   the agents 503 while the server restarts and logs `sink rejected` with reason `transport` or
   `timeout`. It passes only from the server's stop to 30 seconds after it is back, so a fault on
   the restarted server still fails the check.
7. **Test run.** `shop-test` leaves every finding as it was, and the never-hit
   `Pricing.applyLegacyDiscount` reads as called only by tests, naming `SoakTestDriver`.

8. **Windows.** The supervisor reads every finding live every 5 seconds for 150 seconds after each
   instance start (a graceful restart, a `kill -9` restart, an upgrade and a crash cycle included),
   collector start and server restart, and reads the report and the snapshot-served routes once a minute
   for 20 minutes. These are real seconds at any scale. One thread reads, and a tick is skipped when the
   previous read is still running. The reader starts before the first instance. Each read is judged by
   check 2's rule (every finding row is named in `truth.json`, within its time window, with a dependency
   held to its listed status). Check 1's planted answers are left to the six-hourly checks, since a run
   that has not delivered its counts holds those claims back on purpose. Until T0 plus the lag, a read
   judges only never-loaded rows and dependency statuses: before their planned first requests, the
   later routes' never-called and never-hit rows are true findings that `truth.json` does not name. A
   live read applies no removal, so it excuses a row about code a `removed` entry names. A violation is recorded with its time and the event that opened the
   window, in `state/windows.json`, and fails the check, and so do failed reads above a tenth of all
   reads. The check passes with the counts of reads made. While the collector refuses manifests,
   `shop-1`'s row on `/instances` is read every 5 seconds, and one read must show its run as counts
   behind (no delta batch yet, or `counts_behind_since` set). It covers what the six-hourly
   checks miss: evidence that arrives later than the data that defines what is judged, such as a static
   baseline that reaches the server before the first manifest of the same run.

A failed check is triaged: a wrong `truth.json` entry is fixed there, and a product defect is a bug.

## Not covered

- A class or a parameter that a supplier supplied, and that the server keeps only while the supplier's
  run lives, read after that run expires. Reproducing it needs the server to prune runs, which the soak
  does not do inside a week.

## truth.json

```json
{
  "services": {
    "soak-shop": {
      "prod": {
        "never_hit_methods": [{"class": "dev.soaktest.shop.Pricing", "method": "applyLegacyDiscount"}],
        "never_hit_outcomes": [{"class": "...", "method": "...", "condition_contains": "id < 0"}],
        "never_called_endpoints": [{"verb": "DELETE", "template": "/orders/{id}", "until": null}],
        "live_endpoints": [{"verb": "GET", "template": "/orders/{id}", "from": "PT0S"}],
        "never_loaded": ["dev.soaktest.shop.LegacyCsvExporter"],
        "never_initialized": ["dev.soaktest.shop.TaxTables"],
        "never_instantiated": ["dev.soaktest.shop.AuditRecord"],
        "failed_to_initialize": ["dev.soaktest.shop.BrokenConfig"],
        "never_supplied": [{"class": "...", "method": "search", "parameter": "sort"}],
        "always_supplied": [{"class": "...", "method": "search", "parameter": "limit"}],
        "dependencies": [{"artifact": "commons-csv", "status": "unreached"}],
        "stale_hit": [{"class": "...", "method": "...", "days": 1, "from": "PT26H"}],
        "unreached_cluster_roots": [{"class": "...", "method": "rebuild", "members_include": ["..."]}],
        "removed": [],
        "never_in_findings": [{"class_pattern": "...OrderView", "methods": ["copy", "component1", "component2"]}]
      }
    }
  }
}
```

`live_methods` lists methods that must be on no never-hit finding from their `from`, and a dependency may
carry `site_class`, a class that must be among its sites.
`from` and `until` are ISO 8601 durations from T0, so an answer can change at a known time. The
apps' authors fill every list. A list the schema names and the apps do not need stays empty.
