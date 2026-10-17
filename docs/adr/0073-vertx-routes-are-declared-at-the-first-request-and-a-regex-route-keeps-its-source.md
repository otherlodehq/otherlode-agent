---
status: accepted
---

# Vert.x routes are declared at a router's first request, and a regex route keeps its source

Decided on 2026-10-17 in a grill on the endpoint follow-ups. Vert.x Web breaks two things ADR 0017's other modules rely on: a moment when registration is finished, and a single route that a request matched.

A route enters a router the first time a handler is set on it (`RouteImpl.checkAdd`, vertx-web 4.5.35 RouteImpl.java:386-391), but its path, verbs and further handlers can change afterwards, and dispatch reads whatever the route holds at the time (RouteImpl.java:82-122, 40). A sub-router mount looks like an ordinary route when it is added, since `subRouter` marks the route exclusive only after setting its handlers (RouteImpl.java:177-186). Routes can be removed, cleared or added at any time, and nothing signals that routing is complete. One request can also run through several routes in order, each handler calling `next()`, and nothing at match time says which route will end the request.

- **One `vertx-web` module for 4.5.0 to 5.x**, compiled against 4.5.0. Every hook is the same from 4.0.0 to master, as OpenTelemetry's single Vert.x Web instrumentation shows. 4.0 to 4.4 are documented as untested.
- **Declared at a root router's first request.** The module tracks routers as they are built: routes added and removed, sub-routers mounted (from `RouteImpl.subRouter`, which every version has), handlers set. At a root router's first `RouterImpl.handle(HttpServerRequest)` it walks every router reachable from that root and declares their routes; a later request re-walks a reachable router that changed. An instance that never receives a request declares nothing, which costs nothing across a fleet, since identity merges across instances.
- **An endpoint is a route with a handler that is not a sub-router mount.** A route with no path is declared as `/*` under its mount. A route counts when `RouteState.matches` returns 0 for a request that is not a failure, on a route that is not a mount. A pass-through route, such as a body handler with no path, becomes an endpoint that every request hits: noise, never a false "called".
- **A regex route keeps its source.** A route made with `routeWithRegex` or `pathRegex` has no path a template can come from. It is declared and counted under its regex source exactly as written, and `EndpointLocation` carries an additive field that marks the template as a regex, so no reader treats it as a path. vertx-web-openapi 4.x makes every operation with path parameters a regex route, so leaving them undeclared would leave most of such an application out. An endpoint's identity, ADR 0017's verb and template, gains whether the template is a regex, so a regex never merges with a path template that reads the same.
- **Nothing is retracted.** A route removed without ever being called reads as never called, which is true.
- **`:name` becomes `{name}` in the module**, with a 5.2 type suffix (`:id::integer`) dropped like a regex constraint, before the shared normaliser runs. A colon rule in the shared normaliser would break Spring's literal colons.
- **The handler join** names the route's last handler that is not one of Vert.x Web's own priority handlers (body, authentication, security policy and the like). `io.vertx.core.Handler` joins ADR 0035's handler interfaces. A `blockingHandler` is unwrapped from its own hook, and a `respond(...)` route gets no join, since its handler is Vert.x's own lambda.
- **Quarkus's catch-all routes are kept.** Quarkus REST registers its deployment path and that path with `/*` as Vert.x routes in front of its resources, so each REST request also counts against those. They are routes the framework serves, never a false claim, and the collector can filter them by handler class.
- **The route bridge converts Vert.x colon parameters** before its ownership check, so a request the module counted under `/users/{id}` is not counted again as OpenTelemetry's `/users/:id`.

## Considered options

- **Declaring when a route is added.** Rejected: a mount reads as a leaf route then, and a path, verbs or handlers set afterwards would be missed or wrong.
- **Treating any router not yet mounted as a root at flush time.** Rejected: a router mounted later, or never used, would be published as routes that do not exist, and the wire cannot retract them.
- **Counting only the route that ends the request.** Rejected: it needs an end-of-request hook and counts after the handler ran, against ADR 0017's "counted before the handler runs".
- **Telling pass-through routes apart by handler type.** Rejected: `FormLoginHandler` extends `AuthenticationHandler` and is the terminal handler of a login route, so the rule would drop real endpoints.
- **A reserved prefix inside `route_template` for a regex.** Rejected: a hidden contract every reader would have to know. A field says it on the wire.
- **Leaving regex routes undeclared with a log line.** Rejected: nothing would reach the wire, and a 4.x OpenAPI application would read as having no parameterised endpoints.
