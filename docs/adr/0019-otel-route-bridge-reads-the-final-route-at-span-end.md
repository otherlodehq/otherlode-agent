---
status: accepted
---

# The OpenTelemetry route bridge reads the final `http.route` at span end, is off by default, and never counts an endpoint another module owns

For frameworks Otherlode has no endpoint module for, an opt-in bridge module counts the route the OpenTelemetry Java instrumentation resolved. It weaves advice onto the exit of `HttpServerAttributesExtractor.onEnd`, the one point that runs once per server span with the final route after OpenTelemetry's source-priority rules have settled, and reads the route and request method from the same `HttpRouteState` the extractor reads. A span with no route is not counted. The module's type matcher accepts both the javaagent's relocated copy of `instrumentation-api` and the unshaded library artifact, with the advice's own references remapped to the relocated prefix at bind time, so one module covers both ways of deploying OpenTelemetry. It is enabled by `otelBridgeEnabled`, default false, and an endpoint it reads whose identity already exists from another module is left alone.

## Considered options

- Reading the route through a public OpenTelemetry API from the current span. Not available: the application-facing `Span` is write-only, `ReadableSpan` lives in the relocated SDK, and the only sanctioned way to observe span attributes is a `SpanProcessor` registered through the extension SPI, which requires shipping as an OpenTelemetry extension rather than as a separate agent.
- Advice on `HttpServerRoute.update`. Rejected: it fires several times per request as sources of rising priority override one another, so no single call knows the winner, and reproducing the priority rules would need per-request state.
- Enabled by default, like the framework modules. Rejected: the hook is on OpenTelemetry internals and its correctness is tied to that version, the coupling ADR 0017 declined for v1. An adopter enables it knowingly, for a framework nothing else covers.
- Counting every route the bridge sees. Rejected: a request the Spring module already counted would count twice. Identity is already the cross-module key, so the bridge checks it and hits only entries it created itself, under framework `otel`.

## Consequences

- The bridge counts after the handler ran, not before. "A hit means matched" still holds, since the span ends on an exception too.
- The bridge only ever produces endpoints discovered by dispatch. It has no declared list, so "never called" is not computable for them; that is what a framework module is for.
- A servlet-only application gets the route OpenTelemetry reports for it, which may be a servlet mapping such as `/api/*`, exactly as `http.route` would show.
- The module compiles against the public `opentelemetry-instrumentation-api` artifact at its oldest supported 2.x version; the relocated names are verified against a real agent jar, and any drift trips the module's own linkage-failure disable and lands in `disabled_endpoint_modules`.

## Amendment, 2026-10-07: the cases the bridge skips

The bridge skips a route, binding nothing and counting nothing, in three cases (`EndpointRegistry.recordDispatchIfUnowned`):

- A different framework module already holds the same identity.
- A different framework holds the same template with verb `*`.
- The request is a `HEAD` and a different framework holds the `GET` endpoint of the same template, since frameworks answer `HEAD` from the `GET` handler.

Without the skip, a request a framework module counted would count twice. If the owning module switches itself off after declaring its endpoints, those requests go uncounted. `endpointsEnabled=false`, the one setting that turns modules off, turns the bridge off with them. A servlet context path still slips past the check: OpenTelemetry's route includes it and a framework module's identity does not, so the bridge can record a second endpoint for such a route.

## Amended on 2026-10-18: the bridge removes the prefix OpenTelemetry added

Decided in the servlet module grill (ADR 0074). OpenTelemetry's `http.route` includes the application root, as its HTTP semantic conventions ask ("SHOULD include the application root if there is one"), and for JAX-RS the servlet path too, so Spring's `/orders` reaches the bridge as `/shop/orders` and Jersey's `/users` as `/shop/api/users`. The ownership check missed them, and the bridge recorded a second endpoint discovered by dispatch, with the same traffic counted under both.

The bridge learns the translation from OpenTelemetry's own prefixers. Each takes the unprefixed route and returns the prefixed one: `ServletContextPath.prepend` and `JaxrsContextPath.prepend` in the Java agent's bootstrap, and `HttpRouteSupport.prependContextPath` in the Spring MVC library instrumentation. Advice on their exit records the pair in a table, and the span-end advice resolves the final route through it, transitively, before the ownership check. Routes are low-cardinality by the same conventions, so the table stays small and nothing is held per request. A prefixer the bridge has not read leaves the route as it was, so the failure is the duplicate, never a lost count.

Reading the context path from the `Context` at span end was rejected: on embedded Tomcat and Undertow, OpenTelemetry stores it only on a child context that has closed by then, and the library deployment never stores it. Trying shorter segment suffixes of the route was rejected: an uncovered framework's `/admin/orders` would go uncounted wherever a module owns `/orders`. Stripping context paths the framework modules published was rejected: it is ambiguous with several applications in one JVM.

The Spring WebFlux library instrumentation adds the base path inside `getHttpRoute`, where the unprefixed route is never an argument, so that case stays a duplicate. The Java agent's WebFlux instrumentation adds none.
