---
title: Endpoints
description: Which web frameworks the agent counts endpoints for, how it names an endpoint, when it counts one, and how it joins an endpoint to its handler.
order: 40
---

The agent counts each HTTP endpoint your service serves and lists the endpoints a framework registered, so an endpoint that no request ever reached shows up as never called. A method probe cannot say that. One handler can back two endpoints, a handler can be a lambda with no stable class name, and a handler can sit outside `includePackages`.

Endpoint tracking is separate from the [method and branch probes](methods-and-branches). It does not use `includePackages`, so the endpoints of a framework are reported wherever your handler classes live. It is on by default and needs no setting beyond the agent itself.

## Supported frameworks

Each framework has its own module. A module does nothing on a JVM where its framework is absent. The versions below are the ones the project's tests run against. A version between two tested ones is expected to work and is not tested.

| Framework | Module name | Tested versions | Counted at |
|---|---|---|---|
| Spring MVC, annotated controllers | `spring-webmvc` | Spring Framework 5.3.39, 6.2.19, 7.0.9 | Once the mapping matched the request, before the handler runs |
| Spring MVC, URL-mapped handlers | `spring-webmvc` | same | Once the mapping matched the request, before the handler runs |
| Spring MVC, functional routes | `spring-webmvc` | same | Once the router function matched the request, before the handler runs |
| Spring WebFlux | `spring-webflux` | Spring Framework 5.3.39, 6.2.19, 7.0.9 | Once the mapping matched the request, before the handler runs |
| Javalin 6 | `javalin-6` | 6.1.0, 6.7.0 | Once the request reached its handler, after before-handlers |
| Javalin 7 | `javalin-7` | 7.0.0, 7.2.3 | Once the request reached its handler, after before-handlers |
| Micronaut 3 | `micronaut-3` | 3.0.0, 3.10.15 on Netty. micronaut-servlet 3.0.0 and 3.3.8 on Jetty. micronaut-aws 3.0.0 and 3.17.0 on Lambda | Once Micronaut matched the route, before the filters and argument binding |
| Micronaut 4 and 5 | `micronaut` | 4.0.0, 4.10.32, 5.2.16 | Once Micronaut matched the route, before argument binding |
| Vert.x Web | `vertx-web` | 4.5.0, 4.5.35, 5.0.12, 5.2.1 | When a route matched, before its handler runs; each route a request passes through |
| Ktor 2 | `ktor-2` | 2.3.13 | Once Ktor chose the route, before its handlers run |
| Ktor 3 | `ktor-3` | 3.0.3, 3.5.2 | Once Ktor chose the route, before its handlers run |
| JAX-RS, `javax.ws.rs` | `jaxrs` | Jersey 2.48 with `javax.ws.rs-api` 2.1.1 | Entry of the resource method |
| JAX-RS, `jakarta.ws.rs` | `jaxrs` | Jersey 3.1.12 with `jakarta.ws.rs-api` 3.1.0, Jersey 4.0.3 with `jakarta.ws.rs-api` 4.0.0 | Entry of the resource method |
| Plain servlets, `javax.servlet` | `servlet` | Tomcat 9.0.123 (Servlet 4.0), Jetty 10.0.26 (Servlet 4.0). Jetty 9.4.58 (Servlet 3.1), with the Servlet 3.1 and the 4.0 API jar, shows the module switching itself off | Entry of the servlet's `service` method, against the mapping the container matched |
| Plain servlets, `jakarta.servlet` | `servlet` | Tomcat 10.1.61 (Servlet 6.0), Tomcat 11.0.27 (Servlet 6.1), Jetty 12.0.40 with `ee10` (Servlet 6.0), Undertow 2.3.26 (Servlet 6.0), and Spring Boot 3.5.16 on its embedded Tomcat | Entry of the servlet's `service` method, against the mapping the container matched |
| JDK `com.sun.net.httpserver.HttpServer` | `jdk-httpserver` | JDK 21, the test toolchain | Once the server found the context, before the handler runs |
| Any framework OpenTelemetry instruments | `otel` | `opentelemetry-instrumentation-api` 2.31.1, and the OpenTelemetry Java agent 2.31.1's relocated copy. It compiles against 2.0.0 | Span end, after the handler ran. Off by default |

The module name is what the agent logs, what Otherlode shows as the endpoint's framework, and what appears in the list of [disabled modules](#modules-that-switch-themselves-off). It is also the name [`disabledEndpointModules`](configuration#disabledendpointmodules) takes to keep a module off.

One Spring MVC module covers Spring Framework 5.3 through 7 and both the `javax` and `jakarta` servlet namespaces. The project's demo runs it inside a Spring Boot 4.1.1 fat jar. Spring WebFlux has a module of its own, so a failure in one never switches off the other, and it covers Spring Framework 5.3 through 7 too. The Ktor modules are separate because Ktor 3 replaced `Route` with `RoutingNode`. The Javalin modules are separate because Javalin 7 changed how `ParsedEndpoint` exposes its endpoint, and each module only acts on its own release. One Micronaut module covers Micronaut 4.0 through 5.x, and Micronaut 3 has a module of its own, because its router lists `UriRoute` where Micronaut 4 lists `UriRouteInfo`. Each only acts on its own release, so an application of either is counted by exactly one of them. Micronaut 5 needs JDK 25 or later. One Vert.x Web module covers 4.5 through 5.x, since the classes it hooks are the same in all of them. Vert.x Web 4.0 to 4.4 are not tested. The JAX-RS module reads annotations from either namespace. One servlet module covers `javax.servlet` and `jakarta.servlet`, from Servlet 4.0, and counts the servlets no framework module owns.

Javalin 5 has no module. See [what is not covered](#what-is-not-covered).

## How an endpoint is named

An endpoint is a verb and a route template. Nothing else is part of its identity: not the server, the port, the handler or the framework.

The agent rewrites the framework's own spelling of the template into one form, so the same endpoint compares equal across frameworks, instances and releases:

| Rule | Framework spelling | Template |
|---|---|---|
| Leading slash, no trailing slash | `orders/` | `/orders` |
| Runs of slashes collapse | `//orders//1` | `/orders/1` |
| The root is `/` | an empty path | `/` |
| A path parameter keeps its name and loses its regex | `/orders/{id: \d+}`, `/v{version:\d+}/items` | `/orders/{id}`, `/v{version}/items` |
| A segment that is only a wildcard or a tail becomes `*` | `/files/**`, `/files/{*path}`, `/files/{path...}`, `/{...}` | `/files/*`, `/*` |
| A segment that is only Micronaut's reserved expansion or Javalin's slash-accepting parameter becomes `*` | `/files/{+path}`, `/files/<path>` | `/files/*` |
| Micronaut's optional parameter becomes the optional marker | `/books/{id:?}` | `/books/{id?}` |
| Micronaut's optional segment becomes a segment of its own | `/books{/id}` | `/books/{id?}` |
| Vert.x's `:name` and `:name::type` become a brace parameter | `/users/:id`, `/users/:id::integer` | `/users/{id}` |
| Ktor's optional marker stays | `/{id?}` | `/{id?}` |
| A `*` inside a segment stays | `/files/*.txt` | `/files/*.txt` |
| Case is kept | `/Orders` | `/Orders` |

A form whose meaning would be a guess stays as written: `{.x}`, `{;x}`, `{x*}`, `{a,b}`, `{?q}`, `{&q}`, `{#f}`, `{/a,b}`, a `{+x}` or `<x>` that shares its segment with other text.

The verb is upper case. An endpoint with no verb constraint has the verb `*`. The framework's own spelling travels beside the template for display. The testkit calls it `verbatimTemplate`.

The servlet context path is not part of the template. Spring reports the application-relative template, so a Spring Boot application with `server.servlet.context-path=/shop` still reports `/orders`. Spring WebFlux's `spring.webflux.base-path` is left out the same way. JAX-RS leaves out `@ApplicationPath`, and the servlet module leaves out the context path of the web application. The JDK `HttpServer` is an exception, because a context path is its whole template. Micronaut is the other: [`micronaut.server.context-path`](#micronaut) stays in the template of an annotated route.

A framework mapping that lists several paths or verbs gives one endpoint per combination. `@RequestMapping(path = ["/a", "/b"], method = [GET, POST])` is four endpoints.

Two registrations that name the same verb and template are one endpoint with one count, even from two servers in the same JVM or from two frameworks. The numeric endpoint id the agent assigns is only meaningful inside one run, so Otherlode identifies an endpoint across instances by its verb, its template and its template kind: path, regex or servlet pattern. The framework recorded for the endpoint is the one that registered it first. Two routes that differ only in a part the agent does not read merge for the same reason. Each module below says what it does not read.

A framework can match a route by a regular expression, with no path to read a template from. The agent keeps that expression exactly as written, with no normalisation, as both the template and its display spelling, and marks the endpoint as a regex. A regex endpoint never merges with a path endpoint whose template reads the same, so its identity is the verb, the template and whether the template is a regex.

A servlet mapping that no path can express is a third kind, the servlet pattern: an extension mapping such as `*.do`, which matches at any depth, the default mapping `/`, which matches only what nothing else did, and an exact mapping the naming rules would change or that holds a star or a brace, such as `/orders/` or `/api*`, which a container matches literally. The agent keeps the pattern exactly as written, as the template and its display spelling, and marks the endpoint as a servlet pattern. It never merges with a path or regex endpoint whose template reads the same, so a default mapping `/` and a path endpoint `/` are two endpoints.

## Declared and discovered

The agent learns of an endpoint in one of two ways, and the manifest records which.

An endpoint is declared when the framework registers it. This is what makes "never called" computable: the endpoint exists, a framework would serve it, and no request matched it.

An endpoint is discovered by dispatch when a request matches one that no registration hook reported. The agent creates it on the spot and counts the request, so no hit is dropped. A discovered endpoint has been called by definition. If a registration for the same verb and template arrives later, the endpoint changes to declared.

A module records at most 8,192 endpoints that it finds only at dispatch. Past that, a request for an endpoint the agent does not hold yet records nothing, and the module logs one WARNING that names it and the limit. Requests for endpoints the agent already holds keep counting. Declared endpoints never count toward the limit and are never refused, and a discovered endpoint that is declared later stops counting toward it.

Declared endpoints include the ones your framework registers for itself. Spring's static-resource mappings and Spring Boot Actuator's operations appear in the list, with their handler classes where the agent can name them. Dropping an endpoint because its handler cannot be named would hide it, so the agent reports it unjoined instead.

The route bridge only ever discovers. OpenTelemetry learns a route when a request ends on it, so there is nothing to declare from.

## Spring MVC

Annotated controllers are declared when Spring builds its handler-method table, and when code registers a mapping directly with `registerMapping`. Actuator registers every operation that way. A request is counted against the pattern and verb Spring matched, using its best match when more than one pattern fits. A mapping with no verb is `*`.

A request counts once. These are no call, on every route kind below:

- A lookup Spring makes for itself through `HandlerMappingIntrospector`, which Spring Security and CORS handling use to match the request.
- A lookup made before `DispatcherServlet` has taken the request. The OpenTelemetry Spring MVC instrumentation asks the handler mappings for the route from its filter after the chain returns, so when a filter such as Spring Security answered the request first, that lookup is the first one, and the request is not counted.
- A second lookup of an annotated controller's mapping on a request that already matched one, as the OpenTelemetry Spring MVC library instrumentation makes after the request to learn its route. A forward or an include still counts.
- The second dispatch of a request that completes asynchronously, such as a `Callable` or `DeferredResult` result.
- A CORS preflight, an `OPTIONS` request that carries both `Origin` and `Access-Control-Request-Method`. An `OPTIONS` request without them to a route that maps `OPTIONS` counts.

URL-mapped handlers are `SimpleUrlHandlerMapping`, `BeanNameUrlHandlerMapping` and Spring Boot's static-resource and webjars mappings. Their endpoints always have the verb `*`. Spring falls back to the root handler or a `/*` handler when no registered path matches, and the agent counts that fallback against `/` or `/*`. It does not create an endpoint per requested URL. A root handler a mapping sets itself, as Spring Boot's welcome page mapping does, is declared as `/`. A URL-mapped request that Spring answers before its interceptors run is not counted: a `304 Not Modified` from a `Last-Modified` handler on Spring 5.3 and 6, and a CORS request Spring refuses.

Functional routes are declared by walking the router function once, when the `RouterFunctionMapping` is initialised. That covers `RouterFunction` beans and a mapping built with a router function. The walk reads these predicates:

- `method(...)` gives the verb.
- `path(...)` gives the template.
- `and` combines a path with a path, or a path with a verb.
- `or` declares each alternative.
- A nest's path is a prefix for every route inside it.

Header, parameter, path-extension and `version` predicates narrow nothing, and neither does a negated predicate, so routes that differ only by those merge into one endpoint. A route the walk cannot resolve to a path is not declared, and the agent logs this once at INFO: `a spring-webmvc functional route had no path predicate this walk could resolve; it is counted only if a path predicate matched on the way to it`. Such a route is counted at dispatch when a path predicate matched on the way to it. A route with no path predicate anywhere is not tracked at all.

A functional route the walk did not declare is counted under the request's method when it is `GET`, `HEAD`, `POST`, `PUT`, `PATCH`, `DELETE`, `OPTIONS` or `TRACE`. Any other method counts under `*`.

A top-level or nested `RouterFunctions.resources(pattern, location)` route is declared with the verbs its handler answers, `GET`, `HEAD` and `OPTIONS`, under each prefix its nests give it. It is counted under the prefix the request came through, when the request finds a resource it serves. A request for a resource that does not exist counts nothing, and neither does a verb the handler refuses. A `resources(predicate, resource)` route (Spring 6.1.4 and later) is declared from its predicate's paths and verbs, under its nest's prefix. One whose predicate has no path the walk can resolve is not declared or counted, and the agent logs this once at INFO. A `resources` route over a lookup function of your own is not read, so it is not declared, and the agent logs this once at INFO: `a spring-webmvc resources route had a lookup function this walk could not read; it is not declared, and is counted only when it delegates to Spring's own path lookup`.

## Spring WebFlux

Annotated controllers are declared when Spring builds its handler-method table, and when code registers a mapping directly with `registerMapping`. Actuator registers every operation that way. A request is counted against the pattern and verb Spring matched, using its best match when more than one pattern fits. A mapping with no verb is `*`, and a mapping with no path is `/`.

A request counts once. A CORS preflight is no call, on every route kind: an `OPTIONS` request that carries both `Origin` and `Access-Control-Request-Method`. An `OPTIONS` request without them to a route that maps `OPTIONS` counts. WebFlux has no lookup made on Spring Security's behalf and no second dispatch for an asynchronous result, so neither needs a rule.

URL-mapped handlers are `SimpleUrlHandlerMapping` and Spring Boot's static-resource and webjars mappings. Their endpoints always have the verb `*`. A request is counted against the registered pattern that matched it. WebFlux has no root handler and no default handler, so a request that no registered pattern matches counts nothing. A WebSocket handler mapped by URL is declared like any other and counted when a request upgrades to it.

Functional routes are declared by walking the router function once, when the `RouterFunctionMapping` is initialised, and the walk reads the same predicates as Spring MVC's: `method(...)`, `path(...)`, `and`, `or` and a nest's prefix. Header, `queryParam`, path-extension and `version` predicates narrow nothing, and neither does a negated predicate, so routes that differ only by those merge into one endpoint. A route the walk cannot resolve to a path is not declared, and the agent logs this once at INFO: `a spring-webflux functional route had no path predicate this walk could resolve; it is counted only if a path predicate matched on the way to it`. A mapping built with a router function, as Spring Boot's welcome page mapping is, is declared too. A route the walk did not declare is counted under the request's method when it is `GET`, `HEAD`, `POST`, `PUT`, `PATCH`, `DELETE`, `OPTIONS` or `TRACE`. Any other method counts under `*`.

A top-level or nested `RouterFunctions.resources(pattern, location)` route is declared with the verbs its handler answers, `GET`, `HEAD` and `OPTIONS`, under each prefix its nests give it. It is counted under the prefix the request came through, when the lookup finds a resource and so emits it. A request for a resource that does not exist counts nothing, and neither does a verb the handler refuses. A `resources(predicate, resource)` route (Spring 6.1.4 and later) is declared from its predicate's paths and verbs. A `resources` route over a lookup function of your own is not read, so it is not declared, and the agent logs this once at INFO: `a spring-webflux resources route had a lookup function this walk could not read; it is not declared, and is counted only when it delegates to Spring's own path lookup`.

## Ktor

Ktor routes are declared when the routing tree is built. Each `get`, `post` or `handle` call that attaches a handler registers its route. A route node with no handler is not an endpoint. A request is counted once, when Ktor has chosen the winning route and before that route's handlers run. A request that matches no route counts nothing.

The agent builds the template by walking from the route up to the root. Constant segments, parameters (with any prefix or suffix text), optional parameters, tailcards and wildcards each add a segment. The verb comes from the nearest HTTP method selector, or is `*` when the route has none. Other selectors add nothing to the template, such as headers, content type, query parameters and host. Routes that differ only by those selectors merge into one endpoint.

## Javalin

Javalin routes are declared when Javalin registers them. That covers every verb method on the app or its config, `ApiBuilder` routes with their prefixes, `CrudHandler` routes, and the routes Javalin and its plugins add for themselves. `HttpAllowedMethodsPlugin`'s `OPTIONS` routes are declared and counted like any other. Only HTTP routes are endpoints. `before`, `after`, `beforeMatched` and `afterMatched` handlers are not, and neither are `ws` routes, whose upgrade never reaches the point where the agent counts.

A request is counted once it reached the handler of the route Javalin matched, after the before-handlers ran. Javalin's own match is compiled into its request lifecycle and cannot be hooked, so the count comes from the call into the matched route. These requests count nothing:

- A request a before-handler rejects by throwing, redirects, or ends with `skipRemainingHandlers()`. A Javalin hit means the request reached its handler.
- A request no route matches, such as the 404 for an unknown path.
- A `HEAD` request to a path that has a `GET` route and no `HEAD` route. Javalin answers it without running the `GET` handler. An explicit `head()` route is its own endpoint and counts as `HEAD`.
- A CORS preflight to a path with no `OPTIONS` route. `CorsPlugin` writes its headers in a before-handler and turns the 404 that follows into a 200. A preflight to a path that has an `OPTIONS` route, such as one `HttpAllowedMethodsPlugin` adds, reaches that route and counts as `OPTIONS`, never as the verb it asks about.

The template is the path as registered, after `ApiBuilder` composed its prefixes. A `{id}` parameter keeps its name, a segment that is only `<path>` becomes `*`, and a trailing slash is dropped. Javalin's context path is not part of the template.

`javalin-6` covers Javalin 6.1 and later. Javalin 6.0.0 and 6.0.1 keep a method the module reads internal, so on those releases the module switches itself off at the first registration, with the kind `LINKAGE_ERROR`. Each module accepts only its own release, so an application of either major is counted by exactly one of them. Javalin 5 has no module.

## Micronaut

Micronaut routes are declared once Micronaut has built its router and the runtime has constructed a dispatch point the module counts at, in either order. That covers annotated controller methods, routes a `RouteBuilder` adds, and the endpoints Micronaut adds for itself, such as its management endpoints. Only routes that serve a URI are endpoints. `@Error` and status routes and filters are not.

The wait is what keeps the report honest. A router that no counted dispatch point is ever built for would declare endpoints nothing can count, and each would read as never called. Micronaut 4 and 5 count at `RouteExecutor`, which every runtime built on Micronaut's request lifecycle constructs: Netty, micronaut-servlet and the serverless runtimes on top of either. A router that has waited one flush interval with no counted dispatch point constructed, or that is still waiting at the agent's final flush, reports the module as disabled with the kind `ROUTES_NOT_COUNTED`, and the module declares nothing from then on. Every start-up builds the router a few milliseconds before its dispatch point, which the wait covers. The report cannot be taken back once sent, so the module stays off for the rest of the process even if a dispatch point is constructed later.

A request is counted once Micronaut matched it to a route, after the pre-matching filters and before argument binding. A hit means matched: a filter that rejects the request afterwards, as Micronaut Security does, still counts, and so does a handler that throws. These requests count nothing:

- A CORS preflight that Micronaut's CORS filter answers (4.6 and later), and an `OPTIONS` request the server answers itself. Before 4.6 a preflight to a path with no `OPTIONS` route matches nothing.
- A request no route matches (404), one whose verb no route accepts (405), one the route's `@Consumes` or `@Produces` refuses (415, 406), and one that matches several routes equally.
- A request that a pre-matching filter answers.

Micronaut adds a `HEAD` route beside every `@Get` unless the annotation sets `headRoute = false`. That route is not declared, and a `HEAD` request that matches it counts against the `GET` endpoint of the same template. An explicit `@Head` method and a `HEAD` route a `RouteBuilder` adds are endpoints of their own, counted as `HEAD`.

A `@ServerWebSocket` route is not an endpoint, because the upgrade never reaches the point where the agent counts. A plain `GET` to a WebSocket path counts nothing either.

`micronaut.server.context-path` is part of an annotated route's template, because Micronaut matches it as part of the path: with `/shop` set, a controller at `/orders` reports `/shop/orders`. A route a `RouteBuilder` registers does not carry the context path, so it reports the path it was given.

The template is the path as Micronaut matches it, without any query variables. `{id:?}` and `{/id}` read as an optional parameter, and a segment that is only `{+path}` reads as `*`; the [naming rules](#how-an-endpoint-is-named) give the forms. Routes that differ only by what they produce or consume, an API version or a port merge into one endpoint, since the agent reads none of them.

### Micronaut 3

`micronaut-3` declares and names routes as `micronaut` does, and counts a request when the server hands the matched route on: Netty's `RoutingInBoundHandler` for a URI route, micronaut-servlet's `ServletHttpHandler` on the servlet containers, and micronaut-aws's `MicronautLambdaContainerHandler` on Lambda, which builds its own route executor and reaches neither. Each counts after the route is matched and before the filters and the handler, so a filter that rejects the request afterwards still counts. Its routes are declared once one of those three has been constructed, and a runtime that constructs none is reported with the kind `ROUTES_NOT_COUNTED` as `micronaut` is. A 404, a 405, a 415, a 406, an `@Error` or status route and a CORS preflight count nothing. A preflight to a path with no `OPTIONS` route reaches no route on Netty or Lambda. micronaut-servlet 3.3 and later dispatches it to a route of another verb, which the agent does not count, and earlier releases answer it with a 405.

Micronaut 3 does not turn a `default` method of an interface into a route, so a `@Get` on one is neither declared nor served. The servlet support is tested on Jetty; Tomcat and Undertow run the same handler class. The Lambda support is tested by driving `MicronautLambdaHandler` for micronaut-aws 3.x.

## Vert.x Web

Vert.x Web routes are declared when a router receives its first request, not when a route is built. A route enters a router when its first handler is set, but its path, its verbs and its handlers can change afterwards, a sub-router mount looks like any other route until `subRouter` marks it, and Vert.x never says routing is complete. So the agent follows the routers as the application builds them. When a root router, the one handed to the server, receives its first request, the agent declares every route reachable from it through mounts, before the request is matched. When a router reachable from that root changes later, by a route added, removed or changed, or a router mounted, the root declares again at its next request. An instance whose routers never receive a request declares nothing.

An endpoint is a route with a context handler that is not a sub-router mount. A route with only a failure handler is not an endpoint. A route with no path is declared as `/*` under its mount. A route with no verb has the verb `*`, and a route that lists several verbs is one endpoint for each.

The template is the mount path of each sub-router the route sits in, followed by the route's path. `:name` becomes `{name}`, and Vert.x Web 5.2's `:name::type` becomes `{name}` too, in a route's path and in a mount's. The name pattern is the one Vert.x reads its own paths with, including the `io.vertx.web.route.param.extended-pattern` system property. A path that ends in `*` keeps it.

A route matched by a regular expression (`routeWithRegex`, `pathRegex` and the verb forms such as `getWithRegex`) is declared and counted under its regex source exactly as written, and marked as a regex. Under a mount, the template is the mount's path followed by the regex source. The name a route is given is never the template, although vertx-web-openapi 4.x names a regex route after its OpenAPI path.

A router used in more than one place, as a root or mounted, declares one endpoint for each place, and a request counts against the one it came through. A parameterised mount gives the routes under it its matched values as their mount point, so a request through it is told apart from the router's other places, but two parameterised mounts of one router cannot be told apart from each other. The agent does not guess: such a router switches the module off with the kind `ROUTE_WALK_FAILED`. A route that is being mounted is not declared until the mount is complete. One case is counted against the wrong place: a router mounted both at a literal path and at a parameterised path that matches the same request, such as `/u/1/*` and `/u/:id/*`, counts that request against the literal one.

A request is counted each time a route matches it, before the handler runs, so a request that passes through several routes counts each of them. These count nothing:

- A request that matches no route, or one a route rejects for its verb, its content type or its `Accept` header.
- The failure pass after a handler calls `fail()`.
- A sub-router mount, which is no endpoint.

A handler that calls `next()` sends the request on to the next matching route, and that route counts too. A `reroute()` restarts routing, so the routes it passes through count again. A route with no path, such as a body handler or a logger, is an endpoint with the verb `*` and the template `/*` that every request reaches. It is never a false "called", but it is noise. Several routes with one verb and template are one endpoint, and its count is the number of times a request matched any of them.

Quarkus serves its REST resources through a Vert.x Web router, and registers its deployment path and that path with `/*` as routes in front of them. Each REST request therefore also counts against those two. They are routes the framework serves, and the collector can filter them by handler class.

A route that is removed or cleared is not retracted. It stays declared and reads as never called, which is true.

## JAX-RS

JAX-RS has no registration call, so the agent reads the annotations when a resource class loads and declares the routes then. It reports them once it has seen the class defined, or once one of them is called, so a resource class that fails to load reports no routes. A request is counted when the resource method is entered, which a JAX-RS server does after it matches the request. A call made directly from your own code also counts.

A class is read when all of these hold:

- It is concrete, not abstract and not an interface.
- It, or a supertype, carries `@Path` or declares a method carrying `@Path` or a verb annotation (`@GET`, `@POST`, `@PUT`, `@DELETE`, `@HEAD`, `@OPTIONS`, `@PATCH`).
- Its class loader can see `jakarta.ws.rs.Path` or `javax.ws.rs.Path`.

A method becomes an endpoint when it has a verb or `@Path`, declared on itself or inherited as described below. A method with `@Path` and no verb is a sub-resource locator and is declared with the verb `*`. A custom annotation meta-annotated with `@HttpMethod` gives its value, upper-cased, as the verb. A class whose only routing annotation is such a custom verb, with no `@Path` anywhere, is not read.

The template is the class-level `@Path` joined to the method's `@Path`. A class with no class-level `@Path` in effect is not a root resource, so the agent declares nothing for it. That includes a sub-resource class a locator returns. Calls to it count on the locator's endpoint.

### Inheritance

The agent follows the JAX-RS annotation-inheritance rules for methods and adds one behaviour for classes, which Jersey and Quarkus REST share.

- A method with no JAX-RS annotation of its own inherits the annotations of the method it overrides or implements. The search tries the superclass chain first, then the interfaces in declaration order. Any JAX-RS annotation on the method itself, even `@Produces`, turns inheritance off for that method.
- Only methods the concrete class declares are read. A method body inherited from a superclass without an override is not declared on the subclass.
- A class-level `@Path` on a superclass or interface is used only when Jersey or Quarkus REST (RESTEasy Reactive) is on the resource class's class loader. The specification does not inherit it and classic RESTEasy does not either. Without either, the class declares nothing, and the agent logs at INFO, once for each such class and for at most 16 classes: `class-level @Path on a supertype of <class> is ignored, so it declares no endpoints`. The agent detects Jersey by the presence of `org/glassfish/jersey/server/model/Resource.class` on that loader, and Quarkus REST by `org/jboss/resteasy/reactive/server/core/ResteasyReactiveRequestContext.class`, a class of its server artifact and not of its client. With Jersey present, the agent follows Jersey: a superclass's `@Path` wins over an interface's. With Quarkus REST alone, it follows Quarkus: a direct abstract superclass's `@Path`, else an interface's, and never a concrete superclass's.
- Quarkus REST serves a class that implements an interface carrying the class-level `@Path`. It serves a concrete subclass of an abstract `@Path` class only when the abstract class has exactly one subclass of any kind, abstract ones and subclasses of subclasses included. The agent sees one class at a time and cannot count subclasses, so it declares each direct concrete subclass anyway. Quarkus serves none of them when there are two or more, and Quarkus 3.0 to 3.7 serve no subclass of an abstract class at all, so on those the endpoints read as never called. An interface that inherits its `@Path` from another interface is not followed.
- When two interfaces give a method different verbs or paths, the first in declaration order wins and the agent logs a WARNING once.

Only Jersey is tested for serving. Quarkus REST's server classes are tested only as the marker that lets the agent inherit a class-level `@Path`. A resource served by RESTEasy, Apache CXF or another runtime is declared by the same annotation rules, which the specification defines. No test runs it.

A generated proxy of a resource class, such as a Spring CGLIB proxy or the subclass and client proxy Quarkus ArC generates, is skipped. The proxy calls the real method, so the real class counts.

## Servlet

A plain servlet is counted at its own entry. The agent hooks the public `service(ServletRequest, ServletResponse)` of every servlet class, `HttpServlet` included, and counts a request there against the mapping the container matched for it, which it reads from `HttpServletRequest.getHttpServletMapping()`. One module covers `javax.servlet` and `jakarta.servlet`. It needs a container that implements Servlet 4.0 or later, since `getHttpServletMapping` is new in 4.0.

A servlet context is declared at the first request it receives, whichever servlet that request reaches, a servlet that is left out included. The agent reads the context's servlet registrations then, and declares every mapping of every registration with the verbs described below, so a mapping that no request ever reaches reads as never called. A context that never receives a request declares nothing. The agent reads the registrations of every context it holds again before each manifest, so a mapping added after the first request is declared within one flush interval. A request that matches it before then counts nothing: a late mapping is counted from the flush after it was added. The servlet API says a mapping cannot be added once a context is initialised, and which containers accept one anyway differs. Through `ServletRegistration.addMapping`, Tomcat 9.0, 10.1 and 11.0 and Jetty 10 refuse it with an `IllegalStateException`, and Jetty 12 (`ee10`) and Undertow 2.3 accept it. A mapping a container adds by its own means, as Tomcat's `Context.addServletMappingDecoded` and Jetty's `ServletHandler.addServletWithMapping` do, is accepted by Tomcat and Jetty and declared the same way.

The template follows the mapping:

| Mapping | Template | Kind |
|---|---|---|
| Exact, `/orders` | `/orders` | Path |
| Path, `/api/*` | `/api/*` | Path |
| Context root, the empty string | `/` | Path |
| Extension, `*.do` | `*.do` | Servlet pattern |
| Default, `/` | `/` | Servlet pattern |
| Exact that [the naming rules](#how-an-endpoint-is-named) would change or read as a wildcard or parameter, `/orders/`, `/api*`, `/a{b}`, `/a/**` | as written | Servlet pattern |

An exact or path mapping is a path template only when the naming rules leave it as it is and no star or brace stands before a final `/*`. A container matches an exact mapping literally, so `/orders/` is not `/orders`, and `/api*` and `/a{b}` name one path each, with a star and braces in it. Reading them as path templates would merge `/orders/` with `/orders`, or show a literal path as a wildcard or a parameter. They are kept exactly as written, under the servlet pattern kind, as extension and default mappings are.

The context path is not part of it. Two web applications in one container that map the same pattern are one endpoint.

The verbs are those of the `do*` methods that the servlet's class and its superclasses below `HttpServlet` declare. The agent reads them from the class files, and loads and reflects on nothing. `HttpServlet.doOptions` reads `doGet`, `doPost`, `doPut` and `doDelete` the same way to build `Allow`. It always allows `OPTIONS` and `TRACE` and never reads `doHead`, which the agent treats as below.

- `doGet` declares `GET`. A `HEAD` request counts against it, unless the class declares `doHead`, which makes `HEAD` a verb of its own.
- `doPost`, `doPut` and `doDelete` declare their verbs.
- `doPatch` declares `PATCH` only where the servlet API's own `HttpServlet` declares it, which is Servlet 6.1: Tomcat 11 counts it, and Tomcat 9.0 and 10.1 do not. Before 6.1, `HttpServlet.service` answers `PATCH` itself with a 501.
- `doOptions` and `doTrace` declare a verb only when the class declares them.
- A class that overrides `service`, with either signature, below `HttpServlet`, and a servlet that is not an `HttpServlet`, have the verb `*`.

A request whose verb the servlet does not declare counts nothing, a 405 for example. A `do*` method that only calls `super` reads as served. The handler is the `do*` method that serves the verb, or `service` for a `*` servlet, as the class file that declares it has it.

These count nothing:

- A forward, an include, an asynchronous dispatch (`startAsync` and then `dispatch`) and an error page dispatch. Only the original `REQUEST` dispatch counts, so a request counts once and an error page is not a call. Each is tested on each container listed above, Undertow included.
- A request a filter answers before it reaches the servlet.
- A request to a servlet that is left out, as listed next.

A servlet whose `service` calls `super.service`, or whose class overrides the protected `service`, still counts a request once. So does a servlet that calls another servlet's public `service` directly, as long as the container initialised the other one, since the call enters the other servlet's hook with the first servlet's mapping and the agent counts only in the servlet whose configured name is the matched one. A servlet that has no `ServletConfig`, or whose config names no servlet, always counts. A servlet called directly before the container initialised it therefore counts the request a second time.

Some servlets are left out, whatever the state of the module that counts behind them:

- Servlets another module counts behind: Spring MVC's `DispatcherServlet` and every subclass of it, Jersey's `ServletContainer`, RESTEasy's `HttpServletDispatcher` and `HttpServlet30Dispatcher`, CXF's `CXFServlet` and `CXFNonSpringJaxrsServlet`, Micronaut's `DefaultMicronautServlet`, Ktor's `ServletApplicationEngine` and Javalin's `JavalinServlet`. The agent matches them by class hierarchy, read from the class files. Ktor's and Javalin's servlets are left out only in a class loader that has the release a module covers: `io/ktor/server/routing/Route.class`, which Ktor 2 and 3 ship and Ktor 1 does not, and `io/javalin/router/ParsedEndpoint.class`, which Javalin 6 and 7 ship and Javalin 5 does not. Javalin 5 and Ktor 1 have no module, so their servlet is counted as a plain servlet, a `*` endpoint on its mapping that takes every hit. Without this a Spring application would report one `* /` endpoint that takes every hit.
- The container's own servlets, matched by exact class name, so an adopter's subclass is counted: Tomcat's `DefaultServlet` and `JspServlet`, Jetty's `DefaultServlet`, `NoJspServlet`, `JettyJspServlet` and `ServletHandler$Default404Servlet` in each of its packages, Undertow's `DefaultServlet` and Quarkus's `QuarkusErrorServlet`. A container not in this list has its default servlet counted as a servlet at `/`, which is noise and never absence.

A framework servlet no module counts behind, such as Vaadin's, JSF's `FacesServlet` or the H2 console, is a plain servlet and is counted as one. The H2 console Spring Boot registers with `spring.h2.console.enabled` is declared as `GET` and `POST` on `/h2-console/*`, and a request to it counts once, in the servlet module only.

The module switches itself off in these cases (see [below](#modules-that-switch-themselves-off)):

- The container implements a servlet API older than 4.0. With a 3.1 API jar `getHttpServletMapping` does not link. With a 4.0 jar over a container that does not implement it, as Spring Boot 2.7 on Jetty 9.4 has, the call answers with the API's default, which has no match type. The first request switches the module off before it declares anything, and the kind is `LINKAGE_ERROR` in both.
- A servlet registration holds a mapping that is none of the five forms above: one that does not start with `/` or `*.`, and is not the empty string. Jetty's own `ServletHandler` refuses a regular expression or URI template spec as a servlet mapping, so only a handler that overrides its `asPathSpec` puts one there. The agent declares nothing from that read, and the kind is `ROUTE_WALK_FAILED`.
- A servlet class's class file cannot be read, so its verbs cannot be known. The kind is `ROUTE_WALK_FAILED`.
- A request reaches a servlet with a mapping that has no match type, though the container implements Servlet 4.0, because a filter hands the servlet a request object that hides the real mapping. The agent declares nothing, and the kind is `ADVICE_FAILED`.

## JDK HttpServer

Every `createContext` call registers an endpoint, with or without a handler. A later `setHandler` attaches the handler to the context. A context is a prefix match with no verb constraint, so the verb is `*` and the template is the context path exactly as registered. A request is counted when the server finds its context, before the handler runs. A request no context matches (the server's own 404) counts nothing.

The module hooks the JDK's internal `sun.net.httpserver` classes, which are not a supported API. On a JDK that changes a method the module hooks, the module switches itself off with the kind `HOOK_UNMATCHED` (see [below](#modules-that-switch-themselves-off)). Only a renamed class goes unseen, since the module then matches nothing.

## Route bridge for OpenTelemetry

Set `otelBridgeEnabled=true` to also count the route OpenTelemetry's HTTP server instrumentation resolved. Use it for a framework the modules above do not cover. It is off by default because it hooks OpenTelemetry's internal classes, so its correctness depends on the OpenTelemetry version and not on your framework.

The bridge works with the OpenTelemetry Java agent and with the `opentelemetry-instrumentation-api` library in your application. It counts at span end, because OpenTelemetry settles a request's route only then. So it counts after the handler ran, and a request that threw still counts. The route is OpenTelemetry's `http.route`, and the verb is the request method. A span with no route counts nothing.

A route OpenTelemetry reports in Vert.x's spelling, `/users/:id`, is read as `/users/{id}` first, where a segment is only `:name` or `:name::type`. Any other colon stays. A request the `vertx-web` module counted under the same template is therefore not counted again. That holds for a route at a static path. OpenTelemetry drops a trailing `/`, so `/files/*` reaches the bridge as `/files`; it reports a parameterised mount with its matched values, so each value becomes a bridge endpoint of its own; and a colon inside a segment, `/:from-:to`, stays. Each of those is counted by both.

The bridge never discovers an endpoint another module owns. It skips a route when a framework module already holds the same identity, when a framework holds the same template with the verb `*`, and when a `HEAD` request names a template whose `GET` endpoint a framework holds. Otherwise a request that a framework module counted would count twice. If the owning module is switched off, those requests go uncounted.

OpenTelemetry spells a servlet's extension mapping `*.do` as `/*.do`, and the default mapping `/` as `/*`, or as `/` for a request on the context root. The bridge reads `/*.do` as the `servlet` module's pattern `*.do`, and `/*` and `/` as its pattern `/`, so a request the `servlet` module counted at one of those mappings is not counted again. A real path mapping `/*` is a path template, which the `servlet` module declares as `/*`, and the ordinary identity match covers it.

OpenTelemetry puts the application root in front of a route, as its HTTP conventions ask, and for JAX-RS the servlet path too. A Spring application under the context path `/shop` reaches the bridge as `/shop/orders`, and a Jersey resource at `/users` behind a servlet mapped at `/api/*` as `/shop/api/users`. The bridge removes the prefix OpenTelemetry added, so the routes read as the framework modules declare them, `/orders` and `/users`, and a servlet mapping as `/api/*`. It does this for the servlet instrumentation, for JAX-RS (Jersey, CXF and RESTEasy) and for the Spring MVC library instrumentation, by watching the prefixers OpenTelemetry itself calls. A request the framework module or the `servlet` module counted is not counted again. A deployment that uses none of those prefixers is unaffected, and a prefixer the bridge does not know leaves the route as OpenTelemetry reported it, so the route can read as a second endpoint.

The prefix of the Spring WebFlux library instrumentation is not removed. It adds the base path inside the code that picks the route, where the route without it is never available, so a WebFlux application with a base path can report the route with the base path beside the `spring-webflux` module's route without it. The OpenTelemetry Java agent's WebFlux instrumentation adds no base path.

## The handler

An endpoint names the method that serves it where the framework lets the agent find one. A consumer joins the endpoint to that method's probe, so a method that never ran can list the endpoints it would serve. The join is a label on the endpoint. Counting does not depend on it.

| Module | What the endpoint names |
|---|---|
| Spring, annotated | The class that declares the method (the user class, not a CGLIB proxy), the method and its descriptor. A mapping inherited from a base controller names the base class. |
| Spring, URL-mapped | The handler's class only, with no method. |
| Spring, functional | `handle` and the class that declares it. |
| Javalin, named handler class | `handle` and the class that declares it. |
| Javalin, lambda or method reference | The method the lambda calls, or the method the reference names. |
| Javalin, `CrudHandler` | The handler's method for the route: `getOne`, `getAll`, `create`, `update` or `delete`, with the class that declares it. |
| Javalin, `SseHandler`, `VueComponent` and any `VueHandler` that does not override `handle` | Nothing. Javalin's own wrapper runs the handler. |
| Micronaut 3, 4 and 5 | The class that declares the method Micronaut runs, the method and its descriptor, as the class file has them. A method a controller inherits from a base class or an interface names that base class or interface, a controller with AOP advice names the controller and not Micronaut's generated subclass, and a Kotlin `suspend` function has the continuation as its last parameter and `Object` as its return type. |
| Vert.x Web | The route's last context handler that is not one of Vert.x Web's priority handlers (platform, security policy, body, multi-tenant, authentication, input trust and authorization): `handle` and the class that declares it, or the method a lambda or method reference calls. A `blockingHandler` names the handler it wraps. Nothing for a `respond(...)` route, for a handler that is a class of Vert.x Web's own, such as `StaticHandler`, or for one a library wraps before Vert.x sees it: the RxJava and Mutiny APIs, and the OpenTelemetry Java agent when it is attached after this agent. |
| Ktor | The handler body's class and `invokeSuspend`. A handler class with no `invokeSuspend` names the class alone. |
| JAX-RS | The concrete class, the method and its descriptor. |
| JDK `HttpServer` | `handle` and the class that declares it. |
| OpenTelemetry bridge | Nothing. |

The agent skips the join rather than guess. A Spring mapping registered under a bean name whose type cannot be resolved is still an endpoint, with no handler. The same holds for any endpoint the agent finds at dispatch with no registration to read.

Once an endpoint names a method, a later registration for the same identity does not replace it. If two handlers register the same verb and template, the first one with a method wins. An endpoint that names only a class gains a method when one is learned.

A functional route written in Spring's Kotlin DSL, `router { ... }` or `coRouter { ... }`, names no handler. Kotlin compiles its handler into one of Spring's own classes, and a name inside Spring joins to nothing your code wrote. The route is still declared and counted.

### Lambdas and method references

A Java or Kotlin lambda or method reference passed as a handler becomes a hidden class with no stable name, so its class name joins to nothing. For the handler types the modules take, the agent instead names the method the lambda calls. These are `HttpHandler` for the JDK `HttpServer`, `HandlerFunction` for Spring functional routes, `Handler` for Javalin and `Handler` for Vert.x Web. It does this by watching the JDK's lambda factory as it creates each such class, then reading the answer back when the framework hands the lambda over.

The reported method is the one the lambda calls, with its real descriptor. Values the lambda captured come first in that descriptor.

A hidden class gets no join in these cases:

- The lambda class was created before the agent started watching.
- The agent could not install the watcher on this JVM. It checks the lambda factory's internals against the running JDK and, if they do not match, logs one WARNING that starts `handler lambdas and method references will not be named on this JVM`. Nothing else breaks.
- The lambda is for a subinterface of the handler type, which the agent does not watch.
- The lambda is a reference to an abstract or interface method, because which method runs depends on the receiver.
- The method's own class is itself hidden.

Ktor handlers are suspend lambdas, which compile to named classes, so Ktor needs none of this.

When a compiler generates a pass-through between the framework and your function, the agent follows it to the one function it forwards to. This covers a Kotlin function reference compiled with class-based SAM conversion and a Scala `$adapted` forwarder. The join then names your function, which has a method probe, and not the generated class. It applies only to the handler types named above.

## Modules that switch themselves off

Each module is built against one version of its framework and does not check the version when it installs. A module switches itself off for the rest of the process when:

- its advice throws for any reason, including a linkage error against a framework version it does not match;
- one of its hooks matches no method on the framework class it hooks, because a framework release renamed or changed the method;
- a framework class it hooks fails to weave.

A lost registration hook would leave endpoints undeclared. A lost dispatch hook is worse: the module still declares every route but never counts a call, so every endpoint of that framework would read as never called. Switching the module off turns that silence into a report. It logs one WARNING:

```text
otherlode: endpoint module ktor-3 disabled itself: <reason>
```

When the cause is a linkage error, the reason is the error with its message, which names the framework class or member that changed, such as `java.lang.NoSuchMethodError: ...getHandler...`. For any other exception the reason in the disabled line and in the report is its class name alone, since a message can hold request text. A servlet mapping outside the forms the servlet specification defines is reported with the mapping.

The agent sends the module's name, the reason and a kind naming why to the collector with the next manifest. The kind separates a linkage error, any other advice failure, a transform failure (the module's own transform threw, or a framework class it hooks failed to weave), a route walk failure, a hook that matched no method (`HOOK_UNMATCHED`), a module your configuration kept off (`CONFIGURED_OFF`), and a Micronaut module whose runtime constructs no dispatch point it counts at (`ROUTES_NOT_COUNTED`). Otherlode can then tell "this service has no endpoints" from "this service's endpoints were not instrumented". The testkit exposes it as `disabledEndpointModules()`, and its endpoint queries mention a disabled module in the error they throw for an unknown endpoint.

A module that [`disabledEndpointModules`](configuration#disabledendpointmodules) or `endpointsEnabled=false` keeps from installing is reported the same way, with the kind `CONFIGURED_OFF`, whether or not its framework is present. It is a choice, not a fault, and it logs no WARNING.

A Micronaut module with the kind `ROUTES_NOT_COUNTED` has declared no endpoint, so the service's Micronaut routes are absent rather than read as never called. It is reported once a router has waited one flush interval with no dispatch point, or at the final flush, and logs the WARNING above.

A module that switches off stops counting but does not retract. The endpoints it already declared stay in the list, and their counts stop, so they read as never called. The disabled-modules list is the only signal for that. Treat any endpoint finding from a service with a disabled module as unreliable for that framework.

A class that fails to weave runs from its original bytes and the agent logs a WARNING naming it. The JAX-RS module is the exception to the switch-off: it hooks your own resource classes, and a class that fails to weave takes its own routes with it, so the module stays on for the rest. If another agent retransforms a class the module already wove and the module cannot weave again, nothing switches off. The agent logs one WARNING for that module and that class goes untracked until it is next transformed.

A framework class that was renamed is not caught. The module never matches it, which reads as the framework being absent. See [troubleshooting](troubleshooting).

## Frameworks with no module

A service whose web framework has no module above reports no endpoints. Without a signal, that reads the same as a service that serves none. So the agent keeps a fixed list of framework markers: a class, or a set of classes, that is defined in the JVM only once a framework's own routing is set up. When it sees every class of a marker loaded, the agent reports the marker's id and the classes that matched on the next manifest, delivered once per run. It never loads a class to look; it reads the names of the classes the JVM has already defined, so a marker is reported only after the class is defined. It looks on every flush, so a marker is reported on the first manifest after its classes load, and on the final flush.

A marker is an observation. It does not say that a framework is uncovered, since a module may count some of its routes, and Otherlode words what it means for the agent version and the modules installed. Markers are reported whatever else runs: a service with a Spring MVC module and a gRPC server reports both the endpoints and the gRPC marker, and a test run reports markers too.

Each claim is narrow. It names the routing the class proves, and says whether the class loads when routing is configured or only when the server starts.

| Marker id | Class that loads | Claim |
|---|---|---|
| `grpc-server` | `io.grpc.internal.ServerImpl$ServerListenerImpl` | A grpc-java server started, an in-process server included. A client alone does not load it. Armeria serving gRPC does not use it |
| `armeria-server` | `com.linecorp.armeria.server.HttpServerPipelineConfigurator` | An Armeria server started. A client alone does not load it |
| `undertow-routing-handler` | `io.undertow.server.RoutingHandler` | Undertow's own `RoutingHandler` configured, whether or not the server started. A server answering through another handler does not load it |
| `javalin-5` | `io.javalin.jetty.JettyServer$start$wsAndHttpHandler$1` and `io.javalin.http.servlet.JavalinServlet` | Javalin 5's own Jetty server started. Javalin 4 loads the first class but not the second, and no Javalin release a module covers has the first. A Javalin 5 application deployed as a servlet in another container loads no marker |
| `play-server` | `play.core.server.PekkoHttpServer` (Play 3), `play.core.server.AkkaHttpServer` (Play 2.8 and 2.9) or `play.core.server.NettyServer` (Play 2.8 to 3.0, with the Netty backend) | A Play server started. Each class is created only when the server is, and binds its port in its constructor. Play's other classes, a router built without a server and the server provider alone do not load it. A marker is reported when any one of the three loads |
| `helidon-webserver` | `io.helidon.webserver.LoomServer$ListenerFuture` | A Helidon 4 `WebServer` started; Helidon 3 and earlier have no marker. A Helidon client in a process with the web server on its classpath does not load it, and neither does a server that was built and not started. Helidon MP starts the same server, so it loads this too; its JAX-RS resources are counted by the JAX-RS module, and the marker says only that Helidon's own `HttpRouting` is serving |
| `struts2-dispatcher` | `org.apache.struts2.dispatcher.ExecuteOperations` (Struts 2.5 and later) or `org.apache.struts2.dispatcher.ng.ExecuteOperations` (Struts 2.3) | A Struts 2 dispatcher filter or servlet started in a servlet container. Only `StrutsPrepareAndExecuteFilter`, `StrutsExecuteFilter` and `StrutsServlet` create it, in their `init`. Struts classes used without one of them, and a filter that is created but not started, do not load it. Struts 2.3's deprecated `FilterDispatcher` creates neither, so an application served through it reports no marker |
| `grails-url-mappings` | `org.grails.web.servlet.mvc.GrailsDispatcherServlet` and Spring Boot's `ServletWebServerApplicationContext` (in `org.springframework.boot.web.servlet.context`, or `org.springframework.boot.web.server.servlet.context` on Spring Boot 4), or, for a Spring Boot 4 application deployed as a WAR, `SpringBootServletInitializer$WarDeploymentApplicationContextFactory` | Grails created the dispatcher servlet that serves its URL-mapped controllers, in a servlet web server context, whether or not the server accepts requests. A Grails process with no web server context, such as a command or a unit test, does not complete the set, though Grails can create the servlet there too. Grails' `@RequestMapping` controllers are counted by the Spring MVC module |
| `webflux-router-without-dispatcher` | `org.springframework.web.reactive.function.server.RouterFunctions$RouterFunctionWebHandler` | A Spring WebFlux router turned into a handler by `RouterFunctions.toHttpHandler` or `toWebHandler`, configured whether or not it was served. A router served by a `DispatcherHandler` does not load it. A test that binds `WebTestClient` to a router function loads it, so that test run reports the marker |

The project's tests run each marker in at least three JVMs with `-Xlog:class+load`: one that uses only the framework's client side, or the framework without its routing, one that configures routing without starting it, and one that serves a request. They run against these releases: grpc-java 1.30.2 to 1.85.0 (five releases), Armeria 1.0.0 to 1.42.0 (five releases), Undertow 2.2.37, 2.3.26 and 2.4.1, Javalin 5.0.0, 5.3.2 and 5.6.5 (and 4.6.8, which must not complete the marker), Spring Framework 5.3.39, 6.2.19 and 7.0.9, Play 3.0.0 and 3.0.12 with Pekko HTTP, Play 2.8.0, 2.8.22, 2.9.0 and 2.9.12 with Akka HTTP, Play 2.8.22, 2.9.12 and 3.0.12 with Netty, Helidon 4.0.0, 4.2.7 and 4.5.5, Struts 2.3.37, 2.5.33, 6.0.0, 6.12.0, 7.0.0 and 7.4.0 in embedded Tomcat, and Grails 6.2.3, 7.0.0, 7.2.4 and 8.0.0 (7.0.0, 7.2.4 and 8.0.0 also deployed as a WAR in Tomcat, and each also run with no web server, which must not complete the marker). A release between two tested ones is expected to behave the same and is not tested.

## Settings

| Option | Default | Effect |
|---|---|---|
| `endpointsEnabled` | `true` | Turns every framework module, and the route bridge with them, off at once when `false`. The agent logs `endpointsEnabled=false, no framework's endpoints will be instrumented` at INFO, and reports each module it would have installed as disabled with the kind `CONFIGURED_OFF`. The method and branch probes are unaffected. |
| `disabledEndpointModules` | None | Module names, separated by `;`. The agent installs none of them and reports each as disabled with the kind `CONFIGURED_OFF`, except `otel` while `otelBridgeEnabled` is off, which was not going to install. |
| `otelBridgeEnabled` | `false` | Turns the OpenTelemetry route bridge on. |

The [configuration options](configuration) page gives the system property and environment variable names for all three.

## What is not covered

- Javalin 5 has no module. Counting its endpoints needs one. For it, `otelBridgeEnabled` counts what OpenTelemetry resolves, discovered at dispatch only, so "never called" cannot be computed. The agent reports the `javalin-5` [marker](#frameworks-with-no-module). A Javalin 5 application deployed as a WAR loads no marker, and its `JavalinServlet` is counted by the `servlet` module as one `*` endpoint on its mapping. The same holds for a Ktor 1 `ServletApplicationEngine`.
- A Spring WebFlux router run through `RouterFunctions.toHttpHandler` or `toWebHandler`, with no `DispatcherHandler`, is not declared, and only its `resources(pattern, location)` routes are counted, found at dispatch. The agent reports the `webflux-router-without-dispatcher` [marker](#frameworks-with-no-module).
- JSP pages are not counted. Each page runs inside the container's one JSP servlet, which the servlet module leaves out.
- The SOAP traffic of Apache CXF's `CXFServlet` is not counted. The servlet is left out, since its JAX-RS requests are counted by the JAX-RS module, and no module counts the rest.
- A container that implements a servlet API older than 4.0, such as Tomcat 8.5 or Jetty 9.4, has no servlet counting. The `servlet` module is reported as disabled with the kind `LINKAGE_ERROR` at the first request, and the application is served as it was. This is tested on Jetty 9.4.58, with its own Servlet 3.1 API jar and with the Servlet 4.0 API jar (`javax.servlet-api` 4.0.1) Spring Boot 2.7 puts beside it.
- Requests that never reach the hooked framework are not counted. A servlet, filter or Tomcat valve that answers first is invisible to the Spring module, for example.
- The agent reads no OpenAPI document and does no static analysis of call sites to find endpoints.
- A route that a router builds at runtime by any means other than the registration points above is found only when a request reaches it.
- JAX-RS runtimes other than Jersey, and Ktor releases other than the ones in the table, have no test run against them.
