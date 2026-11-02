# Soak applications

Two control applications for the soak test, and the answers they plant in `../truth.json`. This is a
Gradle build of its own. The agent's root build does not include it.

The packages are `dev.soaktest.shop` and `dev.soaktest.events`. The agent never probes a class under
`dev.otherlode`, so the include rules in `DESIGN.md` cannot select code in `dev.otherlode.soak.*`.

## Build

From the worktree root:

```sh
./gradlew -p soak/apps build
```

This writes three jars and one library:

- `soak/apps/shop/build/libs/soak-shop.jar`, a Spring Boot fat jar.
- `soak/apps/events/build/libs/soak-events-1.0.0.jar` and `soak-events-2.0.0.jar`, shaded jars.
- `soak/apps/events/build/libs/lib/commons-codec-1.19.0.jar`. The 2.0.0 jar names it in its manifest
  `Class-Path` (`lib/commons-codec-1.19.0.jar`), so it must sit in a `lib` folder beside the jar.

`-PeventsVersion=1.0.0` or `-PeventsVersion=2.0.0` builds only that events jar.

## Run

Both applications read the port from `PORT` and log to stdout. Add the agent in front, as the stack
does: `-javaagent:<agent jar>=...,includePackages=dev.soaktest.shop` for soak-shop and
`includePackages=dev.soaktest.events` for soak-events.

```sh
PORT=8081 java -jar soak/apps/shop/build/libs/soak-shop.jar
PORT=8091 java -jar soak/apps/events/build/libs/soak-events-1.0.0.jar
PORT=8091 java -jar soak/apps/events/build/libs/soak-events-2.0.0.jar
```

Production instances of soak-shop must also pass `excludePackages=dev.soaktest.shop.testdriver`. The
test driver is a class of the same jar. If a production instance includes it, the class reads as
never loaded, and `Pricing.applyLegacyDiscount` stops being a method that only tests call.

### Test driver

```sh
java -cp soak/apps/shop/build/libs/soak-shop.jar \
  -Dloader.main=dev.soaktest.shop.testdriver.SoakTestDriver \
  org.springframework.boot.loader.launch.PropertiesLauncher
```

It calls `Pricing.applyLegacyDiscount` once and exits with status 0. For the `shop-test` instance
put the agent in front, with no `excludePackages`, and add `testRun=true` and
`serviceInstanceId=soak-shop-tests`.

## Traffic rules

- `GET /orders/{id}`: use an id of 0 or more. A negative id takes the `id < 0` side. The tier is
  `id % 3`, so `PLATINUM` is never chosen, whatever the id.
- `POST /orders`: send no `limit` or `sort`. The optional `q` query parameter defaults to `widget`.
- `POST /events`: send JSON like `{"id":7,"kind":"k","payload":"p"}` with
  `Content-Type: application/json`, and a positive `id`. The reply is 201.
- The expected status of every call is in the tables below. `/broken` answers 500 every time.

## soak-shop routes

| Route | Handler | Status | Planted answer |
|---|---|---|---|
| `GET /orders/{id}` | `OrderController.get` | 200 | Live. `OrderService.find` holds a branch whose true side (`id < 0`) never runs, and a `when` on `Tier` whose `PLATINUM` case never runs. Uses guava. |
| `POST /orders` | `OrderController.create` | 201 | Live. Calls `OrderSearch.search(q, limit = 5)`. The lambda `create$lambda$0` never runs. |
| `GET /reports/monthly` | `ReportController.monthly` | 200 | Live. |
| `GET /reports/yearly` | `ReportController.yearly` | 200 | Live, then a stale hit at `days=1` after 24 hours. |
| `GET /once` | `OnceController.once` | 200 | Never called until it is called, live after. |
| `GET /broken` | `BrokenController.broken` | 500 | Touches `BrokenConfig`, whose static initialiser throws. |
| `DELETE /orders/{id}` | `OrderController.delete` | 204 | Never called. |
| `GET /admin/rebuild` | `AdminController.rebuild` | 500 | Never called. The only code that reaches `LegacyCsvExporter`. Answers 500 if called, since commons-csv is taken without commons-io. |
| `GET /health-legacy` | `HealthLegacyServlet.doGet` | 200 | Live. The servlet declares `doPost`, so `POST /health-legacy` is a declared endpoint that is never called. |

## soak-events routes

| Route | Versions | Status | Planted answer |
|---|---|---|---|
| `GET /events` | both | 200 | Live. `EventService.recent` is a suspend function with a branch (`all.size > 10000`) whose true side never runs. |
| `POST /events` | both | 201 | Live. |
| `GET /events/archive` | both | 200 | Never called in prod, live in staging. |
| `GET /events/{id}/replay` | 1.0.0 | 200 | Never called. The only code that reaches `ReplayEngine`. |
| `GET /events/stats` | 2.0.0 | 200 | Live in prod after the upgrade. |

Version 2.0.0 removes the replay route and `ReplayEngine`, and adds the stats route.

## Classes and what the agent says about them

| Class | Planted answer |
|---|---|
| `Pricing.applyLegacyDiscount` | Never hit, in a class that ran. The test driver calls it. |
| `LegacyCsvExporter` | Never loaded. Its methods call commons-csv. |
| `OldPromotions` | Never loaded, referenced by nothing. |
| `TaxTables` (Java) | Loaded through a class literal at startup, never initialised. |
| `AuditRecord` (Java) | Initialised by a static method on every `GET /orders/{id}`, never instantiated. |
| `BrokenConfig` (Java) | Failed to initialise. |
| `OrderSearch.search(q, limit = 10, sort = "asc")` | `limit` always supplied, `sort` never supplied. The class is final. |
| `OrderView`, `Event` (data classes) | `copy` and `componentN` are generated and in no finding. |
| `Event` serializer (kotlinx.serialization) | The `$serializer` members are generated and in no finding. |
| commons-csv | Unreached: loaded once by name at startup, referenced only from `LegacyCsvExporter`. |
| commons-text | Unloaded. |
| `ShutdownHook.close` and `ShutdownAudit.record` | Never hit until the first graceful stop, then live. `close` is a `@PreDestroy` method that sleeps 2 seconds and then calls `record`, which nothing else calls. `Startup` calls `ShutdownAudit.armed()`, so the class loads. |
| `LegacyDigest` (2.0.0) | Never loaded. It refers to commons-codec's `Hex`. |
| commons-codec (2.0.0) | Unreached, with `LegacyDigest` as its site: 2.0.0's `main` loads `Hex` by name, and no 1.0.0 jar refers to the jar. |
| guava | Used. |

The Spring Boot jars in the fat jar also have a status. `../truth.json` lists them with
`"framework": true`.

## How the checks were run

The local runs used the agent jar built from this repository, the stub collector in `demo/` for the
reports, and `-Xlog:class+load` and `-Xlog:class+init`. `LegacyCsvExporter`, `OldPromotions` and
`ReplayEngine` never appear in the class-load log. `TaxTables` appears in the class-load log and
never in the class-init log.
