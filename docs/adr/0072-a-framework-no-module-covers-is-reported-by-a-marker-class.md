---
status: accepted
---

# A framework no module covers is reported by a marker class

Decided on 2026-10-17 in a grill on the endpoint follow-ups. A service whose HTTP framework has no endpoint module reported nothing about endpoints, and the server showed "No known endpoints or entry methods": a missing instrument read as an empty service. Helidon, Play, Armeria, gRPC, Struts, Grails and others will always outnumber the modules.

The agent keeps a fixed list of **framework markers**. A marker is a class, or a set of classes, that loads only once a framework's own routing is set up to serve, together with the narrow claim it supports. When the agent sees every class of a marker load, it reports the marker's identity on the manifest. It sends the observation, never a verdict: the server decides whether that framework's routing is covered and words it, since coverage changes as modules ship and the wire has no retraction.

- **A marker's claim is narrow.** It names the routing the class proves, never the whole framework. Helidon's marker says "Helidon's own `HttpRouting` loaded", not "Helidon is uncovered", because Helidon MP's JAX-RS resources are counted by the JAX-RS module. Grails' says its URL-mapped controllers loaded, since its `@RequestMapping` controllers are counted by the Spring MVC module.
- **A marker loads only when the framework serves.** Not when its jar is on the classpath, and not when a client or utility part of it is used. Where a framework has no class created only once it starts (Helidon's server class loads at `build()`, gRPC's `ServerImpl` likewise), the marker means configured to serve, and the server words it so. Where a class created only at start exists, it is preferred.
- **Each marker is proved before it is listed.** A fixture test runs three JVMs with `-Xlog:class+load`: one that uses only the client side, one that configures the server without starting it, and one that serves. It asserts the marker is absent from the first and present in the last, which also settles whether the verifier loads the class early.
- **Seeing is passive and confirmed.** The agent matches the class name in its transformer and never calls `Class.forName`, which would manufacture the load it reports. A transformer runs before the JVM defines the class, so a marker is published only once the loaded-class sweep confirms the class is defined (ADR 0028).
- **Markers are reported whatever else runs.** With the route bridge on, the framework's routes may arrive discovered by dispatch, which still cannot say "never called", so the marker stands. A test run reports markers too, carried by its test-run flag (ADR 0050).

Before 0.1.0 the list holds the markers the research rated certain and a fixture proves: Play, gRPC, Armeria, Helidon's own routing, Undertow's `RoutingHandler`, Struts 2, Grails' URL mappings, Javalin 5, and a Spring WebFlux router run with no `DispatcherHandler`. A candidate rated less certain (Spring Cloud Gateway on WebFlux, Camel's HTTP consumers, http4k's server adapters) joins only when a fixture raises it. gRPC is in scope although its endpoints are RPC methods: a gRPC-only service otherwise reads as having no endpoints, the misreading this record exists to prevent.

## Considered options

- **A claim that no module covers the framework.** Rejected: Helidon MP, Armeria hosting WebFlux, Grails and Camel's platform-http all load a marker while a module counts some of their routes, so the broad claim would be false.
- **A coverage verdict on the wire.** Rejected: whether a framework is covered depends on the agent version and the modules installed, both already on the wire, and a verdict sent today cannot be withdrawn when a module ships.
- **A marker for plain servlet applications.** Rejected: every servlet container class that loads for a bare servlet also loads for Spring's `DispatcherServlet` and Jersey's `ServletContainer`, so no class tells them apart. Servlets get a module of their own instead, settled in a grill of its own.
- **Markers for Quarkus REST.** Rejected: the JAX-RS module counts its resources, and the inheritance cases it missed are fixed in that module (ADR 0020's 2026-10-17 amendment).
