---
title: Endpoints
description: Which web frameworks the agent counts endpoints for, how it names an endpoint, when it counts one, and how it joins an endpoint to its handler.
order: 40
---

The agent lists the HTTP endpoints each framework in your service registers and counts the requests each one receives, so an endpoint that no request reached reads as never called. Endpoint tracking is on by default. It is separate from the [method and branch probes](methods-and-branches) and ignores `includePackages`, so it reports a framework's endpoints wherever your handler classes live.

## Supported frameworks

Each framework has its own module, and a module does nothing on a JVM where its framework is absent. The tested versions are the ones the project's tests run against. A version between two tested ones is expected to work and is not tested.

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

- `spring-webmvc` covers Spring Framework 5.3 through 7, in both the `javax` and `jakarta` servlet namespaces. The project's demo runs it inside a Spring Boot 4.1.1 fat jar. `spring-webflux` covers Spring Framework 5.3 through 7. A failure in one never switches off the other.
- `micronaut` covers Micronaut 4.0 through 5.x, and `micronaut-3` covers Micronaut 3. Micronaut 5 needs JDK 25 or later.
- Each Javalin, Micronaut and Ktor module acts only on the major releases it covers, so an application is counted by exactly one of them. Javalin 5 has no module.
- `vertx-web` covers Vert.x Web 4.5 through 5.x. Vert.x Web 4.0 to 4.4 are not tested.
- `jaxrs` reads annotations from either namespace.
- `servlet` covers `javax.servlet` and `jakarta.servlet` from Servlet 4.0, and counts the servlets no framework module owns.

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

A brace form the agent does not read keeps its braces and drops any text after a `:` inside them. Examples are `{.x}`, `{;x}`, `{x*}`, `{a,b}`, `{?q}`, `{&q}`, `{#f}`, `{/a,b}`, and a `{+x}` that shares its segment with other text. A `<x>` that shares its segment with other text stays as written.

The verb is upper case. An endpoint with no verb constraint has the verb `*`. The framework's own spelling travels beside the template for display. The testkit calls it `verbatimTemplate`.

The servlet context path is not part of the template. A Spring Boot application with `server.servlet.context-path=/shop` reports `/orders`, not `/shop/orders`. Spring WebFlux's `spring.webflux.base-path`, JAX-RS's `@ApplicationPath` and the web application's context path in the servlet module are left out the same way. Two exceptions keep it: the JDK `HttpServer`, whose template is the context path, and [`micronaut.server.context-path`](#micronaut) on an annotated route.

A framework mapping that lists several paths or verbs gives one endpoint per combination. `@RequestMapping(path = ["/a", "/b"], method = [GET, POST])` is four endpoints.

Two registrations with the same verb, template and template kind are one endpoint with one count, even from two servers in the same JVM or from two frameworks. The endpoint keeps the framework that registered it first. Two routes that differ only in a part the agent does not read merge the same way, and each module below says what it does not read. The numeric endpoint id the agent assigns holds only within one run.

There are three template kinds:

- **Path.** The template follows the naming rules above. Most endpoints are paths.
- **Regex.** A route the framework matches by a regular expression keeps that expression exactly as written, as the template and as its display spelling.
- **Servlet pattern.** A servlet mapping that no path template can express keeps the pattern exactly as written. These are an extension mapping such as `*.do`, the default mapping `/`, and an exact mapping the naming rules would change or that holds a star or a brace, such as `/orders/` or `/api*`.

Endpoints of different kinds never merge, even when their templates read the same. A servlet default mapping `/` and a path endpoint `/` are two endpoints.

## Declared and discovered

The agent learns of an endpoint in one of two ways, and the manifest records which.

An endpoint is declared when the framework registers it. A declared endpoint that no request matched is never called.

An endpoint is discovered by dispatch when a request matches one that no registration reported. The agent creates it then and counts the request, so a discovered endpoint has always been called. If a registration for the same verb and template arrives later, the endpoint becomes declared.

A module records at most 8,192 endpoints that it finds only at dispatch. Past that, a request for an endpoint the agent does not hold yet records nothing, and the module logs this WARNING once:

```text
otherlode: endpoint module <name> found 8192 endpoints at dispatch, its limit; it records no new ones, and the endpoints it holds keep counting
```

Requests for endpoints the agent already holds keep counting. Declared endpoints never count toward the limit and are never refused, and a discovered endpoint that is declared later stops counting toward it. A module also holds at most 8,192 request keys it bound at dispatch. Past that it logs `otherlode: endpoint module <name> bound 8192 request keys at dispatch, its limit; a request with a new key still counts, and the agent resolves its endpoint again each time`, and every request still counts.

Declared endpoints include the ones your framework registers for itself, such as Spring's static-resource mappings and Spring Boot Actuator's operations. An endpoint whose handler the agent cannot name is still reported, with no handler.

The [route bridge](#route-bridge-for-opentelemetry) only ever discovers.

## Spring MVC

Annotated controllers are declared when Spring builds its handler-method table, and when code registers a mapping directly with `registerMapping`, as Actuator does for every operation. A request is counted against the pattern and verb Spring matched, using its best match when more than one pattern fits. A mapping with no verb is `*`.

A request counts once. On every route kind, these count nothing:

- A lookup Spring makes for itself through `HandlerMappingIntrospector`, as Spring Security and CORS handling do.
- A lookup made before `DispatcherServlet` has taken the request. OpenTelemetry's Spring MVC instrumentation makes one from its filter after the chain returns, so when a filter such as Spring Security answered the request first, the request is not counted.
- A second lookup of an annotated controller's mapping on a request that already matched one, such as the lookup OpenTelemetry's Spring MVC library instrumentation makes to learn the route. A forward or an include still counts.
- The second dispatch of a request that completes asynchronously, such as one returning a `Callable` or `DeferredResult`.
- A CORS preflight: an `OPTIONS` request that carries both `Origin` and `Access-Control-Request-Method`. An `OPTIONS` request without them to a route that maps `OPTIONS` counts.

URL-mapped handlers are `SimpleUrlHandlerMapping`, `BeanNameUrlHandlerMapping` and Spring Boot's static-resource and webjars mappings. Their endpoints always have the verb `*`. When Spring falls back to the root handler or a `/*` handler because no registered path matches, the agent counts the request against `/` or `/*`, never against the requested URL. A root handler that a mapping sets itself, as Spring Boot's welcome page mapping does, is declared as `/`. A URL-mapped request that Spring answers before its interceptors run counts nothing: a `304 Not Modified` from a `Last-Modified` handler on Spring 5.3 and 6, and a CORS request Spring refuses.

Functional routes are declared by walking the router function once, when the `RouterFunctionMapping` is initialised. That covers `RouterFunction` beans and a mapping built with a router function. The walk reads these predicates:

- `method(...)` gives the verb.
- `path(...)` gives the template.
- `and` combines a path with a path, or a path with a verb.
- `or` declares each alternative.
- A nest's path is a prefix for every route inside it.

Header, parameter, path-extension and `version` predicates narrow nothing, and neither does a negated predicate, so routes that differ only by those merge into one endpoint. A route the walk cannot resolve to a path is not declared, and the agent logs this once at INFO:

```text
otherlode: a spring-webmvc functional route had no path predicate this walk could resolve; it is counted only if a path predicate matched on the way to it
```

Such a route is counted at dispatch when a path predicate matched on the way to it, under the request's method when that is `GET`, `HEAD`, `POST`, `PUT`, `PATCH`, `DELETE`, `OPTIONS` or `TRACE`, and under `*` otherwise. A route with no path predicate anywhere is not tracked.

A top-level or nested `RouterFunctions.resources(pattern, location)` route is declared as `GET`, `HEAD` and `OPTIONS` under each prefix its nests give it. A request counts under the prefix it came through, and only when it finds a resource. A request for a resource that does not exist counts nothing, and neither does a verb the handler refuses. A `resources(predicate, resource)` route (Spring 6.1.4 and later) is declared from its predicate's paths and verbs, under its nest's prefix. One whose predicate has no path the walk can resolve is neither declared nor counted, and the agent logs this once at INFO. A `resources` route over a lookup function of your own is not declared, and the agent logs this once at INFO:

```text
otherlode: a spring-webmvc resources route had a lookup function this walk could not read; it is not declared, and is counted only when it delegates to Spring's own path lookup
```

## Spring WebFlux

Annotated controllers are declared and counted as in [Spring MVC](#spring-mvc). A mapping with no verb is `*`, and a mapping with no path is `/`. A request counts once. The only request that counts nothing on every route kind is a CORS preflight, an `OPTIONS` request that carries both `Origin` and `Access-Control-Request-Method`. An `OPTIONS` request without them to a route that maps `OPTIONS` counts.

URL-mapped handlers are `SimpleUrlHandlerMapping` and Spring Boot's static-resource and webjars mappings. Their endpoints always have the verb `*`, and a request counts against the registered pattern that matched it. A request that no registered pattern matches counts nothing. A WebSocket handler mapped by URL is declared like any other and counted when a request upgrades to it.

Functional routes are declared by the same walk as Spring MVC's, with the same predicates and the same merging. A mapping built with a router function, such as Spring Boot's welcome page mapping, is declared too. A route the walk cannot resolve is counted as in Spring MVC, and the agent logs `otherlode: a spring-webflux functional route had no path predicate this walk could resolve; it is counted only if a path predicate matched on the way to it` once at INFO.

`resources` routes are declared and counted as in Spring MVC. A `resources` route over a lookup function of your own is not declared, and the agent logs `otherlode: a spring-webflux resources route had a lookup function this walk could not read; it is not declared, and is counted only when it delegates to Spring's own path lookup` once at INFO.

## Ktor

Ktor routes are declared when the routing tree is built. Each `get`, `post` or `handle` call that attaches a handler registers its route. A route node with no handler is not an endpoint. A request is counted once, when Ktor has chosen the winning route and before that route's handlers run. A request that matches no route counts nothing.

The template comes from the route's path up to the root. Constant segments, parameters with any prefix or suffix text, optional parameters, tailcards and wildcards each add a segment. The verb comes from the nearest HTTP method selector, or is `*` when there is none. Other selectors, such as headers, content type, query parameters and host, add nothing, so routes that differ only by those merge into one endpoint.

## Javalin

Javalin routes are declared when Javalin registers them. That covers every verb method on the app or its config, `ApiBuilder` routes with their prefixes, `CrudHandler` routes, and the routes Javalin and its plugins add for themselves, `HttpAllowedMethodsPlugin`'s `OPTIONS` routes included. Only HTTP routes are endpoints. `before`, `after`, `beforeMatched` and `afterMatched` handlers are not, and neither are `ws` routes.

A request is counted once it reaches the handler of the route Javalin matched, after the before-handlers ran. These requests count nothing:

- A request a before-handler rejects by throwing, redirects, or ends with `skipRemainingHandlers()`.
- A request no route matches, such as the 404 for an unknown path.
- A `HEAD` request to a path that has a `GET` route and no `HEAD` route. An explicit `head()` route is its own endpoint and counts as `HEAD`.
- A CORS preflight to a path with no `OPTIONS` route. A preflight to a path that has one, such as one `HttpAllowedMethodsPlugin` adds, counts as `OPTIONS`, never as the verb it asks about.

The template is the path as registered, after `ApiBuilder` added its prefixes. A `{id}` parameter keeps its name, a segment that is only `<path>` becomes `*`, and a trailing slash is dropped. Javalin's context path is not part of the template.

`javalin-6` covers Javalin 6.1 and later. On Javalin 6.0.0 and 6.0.1 it switches itself off at the first registration, with the kind `LINKAGE_ERROR`.

## Micronaut

Micronaut routes are declared once Micronaut has built its router and the runtime has constructed a dispatch point the module counts at, in either order. That covers annotated controller methods, routes a `RouteBuilder` adds, and the endpoints Micronaut adds for itself, such as its management endpoints. Only routes that serve a URI are endpoints. `@Error` and status routes and filters are not.

`micronaut` counts at `RouteExecutor`, which Netty, micronaut-servlet and the serverless runtimes built on either construct. If a router waits one flush interval with no dispatch point constructed, or is still waiting at the agent's final flush, the module reports itself disabled with the kind `ROUTES_NOT_COUNTED` and declares nothing from then on. It stays off for the rest of the process, even if a dispatch point is constructed later.

A request is counted once Micronaut matched it to a route, after the pre-matching filters and before argument binding. A filter that rejects the request afterwards, as Micronaut Security does, does not undo the count, and neither does a handler that throws. These requests count nothing:

- A CORS preflight that Micronaut's CORS filter answers, and an `OPTIONS` request the server answers itself.
- A request no route matches (404), one whose verb no route accepts (405), one the route's `@Consumes` or `@Produces` refuses (415, 406), and one that matches several routes equally.
- A request that a pre-matching filter answers.

The `HEAD` route Micronaut adds beside a `@Get` is not declared, and a `HEAD` request that matches it counts against the `GET` endpoint of the same template. An explicit `@Head` method and a `HEAD` route a `RouteBuilder` adds are endpoints of their own, counted as `HEAD`.

A `@ServerWebSocket` route is not an endpoint, and a plain `GET` to a WebSocket path counts nothing.

`micronaut.server.context-path` is part of an annotated route's template: with `/shop` set, a controller at `/orders` reports `/shop/orders`. A route a `RouteBuilder` registers reports the path it was given, without the context path.

The template is the path Micronaut matches, without any query variables. `{id:?}` and `{/id}` read as an optional parameter, and a segment that is only `{+path}` reads as `*`. The [naming rules](#how-an-endpoint-is-named) give the forms. Routes that differ only by what they produce or consume, an API version or a port merge into one endpoint.

### Micronaut 3

`micronaut-3` declares and names routes as `micronaut` does. It counts a request when the server hands the matched route on, after the route is matched and before the filters and the handler: at Netty's `RoutingInBoundHandler`, at micronaut-servlet's `ServletHttpHandler` on servlet containers, and at micronaut-aws's `MicronautLambdaContainerHandler` on Lambda. Its routes are declared once one of those three has been constructed, and a runtime that constructs none is reported with the kind `ROUTES_NOT_COUNTED`, as for `micronaut`.

A 404, a 405, a 415, a 406, an `@Error` or status route and a CORS preflight count nothing. Micronaut 3 does not serve a `@Get` on a `default` method of an interface, so the agent declares nothing for it. The servlet support is tested on Jetty, and the Lambda support by driving `MicronautLambdaHandler`.

## Vert.x Web

Vert.x Web routes are declared when a root router, the one handed to the server, receives its first request. The agent then declares every route reachable from that root through mounts, before the request is matched. When a router reachable from the root changes later, by a route added, removed or changed, or a router mounted, the root declares again at its next request. An instance whose routers never receive a request declares nothing.

An endpoint is a route with a context handler that is not a sub-router mount. A route with only a failure handler is not an endpoint. A route with no path is declared as `/*` under its mount. A route with no verb has the verb `*`, and a route that lists several verbs is one endpoint for each.

The template is the mount path of each sub-router the route sits in, followed by the route's path. `:name` and Vert.x Web 5.2's `:name::type` become `{name}`, in a route's path and in a mount's. Parameter names are read with the pattern Vert.x uses, including the `io.vertx.web.route.param.extended-pattern` system property. A path that ends in `*` keeps it.

A route matched by a regular expression, through `routeWithRegex`, `pathRegex` or a verb form such as `getWithRegex`, is declared and counted under its regex source exactly as written, as a regex endpoint. Under a mount, the template is the mount's path followed by the regex source. A route's name is never its template.

A router used in more than one place, as a root or mounted, declares one endpoint for each place, and a request counts against the place it came through. A request through a parameterised mount counts against the endpoint declared under that mount, whose template keeps the parameter: a route `/orders` under a mount `/u/:id/*` is `/u/{id}/orders`. A router with two parameterised mounts switches the module off with the kind `ROUTE_WALK_FAILED`, since the agent cannot tell them apart. A route that is being mounted is not declared until the mount is complete. A router mounted both at a literal path and at a parameterised path that match the same request, such as `/u/1/*` and `/u/:id/*`, counts that request against the literal one.

A request is counted each time a route matches it, before the handler runs, so a request that passes through several routes counts each of them. A handler that calls `next()` sends the request on to the next matching route, which counts too, and a `reroute()` counts the routes it passes through again. A route with no path, such as a body handler or a logger, is an endpoint `* /*` that every request reaches. Several routes with one verb and template are one endpoint, whose count is the number of times a request matched any of them. These count nothing:

- A request that matches no route, or one a route rejects for its verb, its content type or its `Accept` header.
- The failure pass after a handler calls `fail()`.
- A sub-router mount.

Quarkus registers its REST deployment path, and that path with `/*`, as routes in front of its REST resources, so each REST request also counts against those two.

A route that is removed or cleared stays declared, with the calls it had.

## JAX-RS

The agent reads the JAX-RS annotations when a resource class loads, and declares the routes once the class is defined or once one of them is called. A resource class that fails to load reports no routes. A request is counted when the resource method is entered. A call made directly from your own code counts too.

A class is read when all of these hold:

- It is concrete, not abstract and not an interface.
- It, or a supertype, carries `@Path` or declares a method carrying `@Path` or a verb annotation (`@GET`, `@POST`, `@PUT`, `@DELETE`, `@HEAD`, `@OPTIONS`, `@PATCH`).
- Its class loader can see `jakarta.ws.rs.Path` or `javax.ws.rs.Path`.

A method becomes an endpoint when it has a verb or `@Path`, on itself or inherited as described below. A method with `@Path` and no verb is a sub-resource locator, declared with the verb `*`. A custom annotation meta-annotated with `@HttpMethod` gives its value, upper-cased, as the verb. A class whose only routing annotation is such a custom verb, with no `@Path` anywhere, is not read.

The template is the class-level `@Path` joined to the method's `@Path`. A class with no class-level `@Path` in effect declares nothing. That includes a sub-resource class a locator returns, whose calls count on the locator's endpoint.

### Inheritance

- A method with no JAX-RS annotation of its own inherits the annotations of the method it overrides or implements. The search tries the superclass chain first, then the interfaces in declaration order. Any JAX-RS annotation on the method itself, even `@Produces`, turns inheritance off for that method.
- Only methods the concrete class declares are read. A method body inherited from a superclass without an override is not declared on the subclass.
- When two interfaces give a method different verbs or paths, the first in declaration order wins and the agent logs a WARNING once.

A class-level `@Path` on a superclass or interface is used only when Jersey or Quarkus REST (RESTEasy Reactive) is on the resource class's class loader. The agent detects Jersey by `org/glassfish/jersey/server/model/Resource.class` and Quarkus REST by `org/jboss/resteasy/reactive/server/core/ResteasyReactiveRequestContext.class`.

- With Jersey present, a superclass's `@Path` wins over an interface's.
- With Quarkus REST alone, the agent uses a direct abstract superclass's `@Path`, else an interface's, and never a concrete superclass's. It declares each direct concrete subclass of an abstract `@Path` class. Quarkus serves such a subclass only when the abstract class has exactly one subclass, and Quarkus 3.0 to 3.7 serve none, so in those cases the endpoints read as never called.
- An interface that inherits its `@Path` from another interface is not followed.
- With neither present, the class declares nothing, and the agent logs this at INFO, once per class and for at most 16 classes:

```text
otherlode: jaxrs: class-level @Path on a supertype of <class> is ignored, so it declares no endpoints: classic RESTEasy and the JAX-RS specification do not inherit it
```

Serving is tested only on Jersey. A resource served by RESTEasy, Apache CXF or another runtime is declared by the same annotation rules.

A generated proxy of a resource class, such as a Spring CGLIB proxy or the subclass and client proxy Quarkus ArC generates, is skipped. The proxy calls the real method, so the real class counts.

## Servlet

A plain servlet is counted at its own entry. The agent hooks the public `service(ServletRequest, ServletResponse)` of every servlet class, `HttpServlet` included, and counts a request there against the mapping the container matched, read from `HttpServletRequest.getHttpServletMapping()`. The module needs a container that implements Servlet 4.0 or later.

A servlet context is declared at its first dispatch of any type, whichever servlet it reaches, a [left-out](#servlets-that-are-left-out) servlet included. The agent then declares every mapping of every servlet registration in the context, so a mapping no request reaches reads as never called. A context that never receives a request declares nothing. Before each manifest the agent reads the registrations of every context again, so a mapping added after the first request is declared at the next flush, and requests to it before then count nothing. When an application is undeployed or its server shuts down, its endpoints stay declared and the module stays on. An application deployed again is declared at its first request.

The template follows the mapping:

| Mapping | Template | Kind |
|---|---|---|
| Exact, `/orders` | `/orders` | Path |
| Path, `/api/*` | `/api/*` | Path |
| Context root, the empty string | `/` | Path |
| Extension, `*.do` | `*.do` | Servlet pattern |
| Default, `/` | `/` | Servlet pattern |
| Exact that [the naming rules](#how-an-endpoint-is-named) would change or read as a wildcard or parameter, `/orders/`, `/api*`, `/a{b}`, `/a/**` | as written | Servlet pattern |

An exact or path mapping is a path template only when the naming rules leave it unchanged and no star or brace stands before a final `/*`. The context path is not part of the template, so two web applications in one container that map the same pattern are one endpoint.

The verbs come from the `do*` methods that the servlet's class and its superclasses below `HttpServlet` declare, read from the class files:

- `doGet` declares `GET`. A `HEAD` request counts against it, unless the class declares `doHead`, which makes `HEAD` a verb of its own.
- `doPost`, `doPut` and `doDelete` declare their verbs.
- `doPatch` declares `PATCH` only where the servlet API's own `HttpServlet` declares it, which is Servlet 6.1. Tomcat 11 counts it, and Tomcat 9.0 and 10.1 do not.
- `doOptions` and `doTrace` declare a verb only when the class declares them.
- A class that overrides `service`, with either signature, below `HttpServlet`, and a servlet that is not an `HttpServlet`, have the verb `*`.

A request whose verb the servlet does not declare counts nothing, a 405 for example. A `do*` method that only calls `super` counts as declared. The handler is the `do*` method that serves the verb, or `service` for a `*` servlet.

A request a filter answers before it reaches a servlet counts nothing, and neither does a request to a left-out servlet.

### Forwards, includes and other dispatches

A forward, an include, an error page dispatch and an asynchronous dispatch count at the servlet they reach, so a servlet that only one of them reaches reads as called. A request that a servlet forwards counts at that servlet and at its target, two endpoints. A dispatch to the endpoint the request already reached counts it again, as `startAsync` and then `dispatch()` with no path does. An error page servlet counts with the verb the container gives the error dispatch: the verb of the failed request on most containers, and `GET` on Tomcat 11. A dispatch made with `getNamedDispatcher` counts nothing.

On an include the agent reads the target's mapping from the request attribute `javax.servlet.include.mapping` or `jakarta.servlet.include.mapping`. When that attribute names another servlet than the one counted, as on Undertow 2.3, the agent picks a mapping from the counted servlet's own registration by the include's servlet path and path info. With no path info it is the mapping equal to the servlet path, then the servlet path followed by `/*`. With path info it is the servlet path followed by `/*`. When none matches, the include counts nothing. So on Undertow an included servlet mapped by extension, as the default servlet or at the context root counts nothing.

A servlet whose `service` calls `super.service`, or whose class overrides the protected `service`, counts a request once. A servlet that calls another initialised servlet's public `service` directly counts once too, since the agent counts only in the servlet whose configured name the mapping matched. A servlet with no `ServletConfig`, or whose config names no servlet, always counts, so one called directly before the container initialised it counts the request a second time. A servlet called directly during an include counts nothing, unless its own mapping is the one the include's path picks.

### Servlets that are left out

These servlets are never counted, whatever the state of any other module:

- Servlets another module counts behind, matched by class hierarchy read from the class files: Spring MVC's `DispatcherServlet` and its subclasses, Jersey's `ServletContainer`, RESTEasy's `HttpServletDispatcher` and `HttpServlet30Dispatcher`, CXF's `CXFServlet` and `CXFNonSpringJaxrsServlet`, Micronaut's `DefaultMicronautServlet`, Ktor's `ServletApplicationEngine` and Javalin's `JavalinServlet`. Ktor's and Javalin's are left out only in a class loader that has `io/ktor/server/routing/Route.class` (Ktor 2 and 3) or `io/javalin/router/ParsedEndpoint.class` (Javalin 6 and 7). The servlet of Ktor 1 or Javalin 5 is counted as a plain servlet, a `*` endpoint on its mapping.
- The container's own servlets, matched by exact class name, so a subclass of yours is counted: Tomcat's `DefaultServlet` and `JspServlet`, Jetty's `DefaultServlet`, `NoJspServlet`, `JettyJspServlet` and `ServletHandler$Default404Servlet` in each of its packages, Undertow's `DefaultServlet` and Quarkus's `QuarkusErrorServlet`. Another container's default servlet is counted as a servlet at `/`.

A framework servlet no module counts behind, such as Vaadin's, JSF's `FacesServlet` or the H2 console, is counted as a plain servlet. The H2 console Spring Boot registers with `spring.h2.console.enabled` is declared as `GET` and `POST` on `/h2-console/*`.

### When the servlet module switches off

The module switches itself off (see [below](#modules-that-switch-themselves-off)) when:

- The container implements a servlet API older than 4.0, with either a 3.1 API jar or a 4.0 API jar over it, as Spring Boot 2.7 on Jetty 9.4 has. The first request switches the module off before it declares anything, with the kind `LINKAGE_ERROR`.
- A servlet registration holds a mapping that is none of the forms above: one that does not start with `/` or `*.` and is not the empty string. The agent declares nothing from that read, and the kind is `ROUTE_WALK_FAILED`.
- A servlet class's class file cannot be read. The kind is `ROUTE_WALK_FAILED`.
- A request reaches a servlet with a mapping that has no match type on a Servlet 4.0 container, because a filter wraps the request and hides the real mapping. The agent declares nothing, and the kind is `ADVICE_FAILED`.

## JDK HttpServer

Every `createContext` call registers an endpoint, with or without a handler, and a later `setHandler` attaches the handler. A context is a prefix match with no verb constraint, so the verb is `*` and the template is the context path under the [naming rules](#how-an-endpoint-is-named): `createContext("/static/")` reports `/static`, and the path as registered travels beside it for display. Contexts `/api` and `/api/` are one endpoint. A request is counted when the server finds its context, before the handler runs. A request no context matches counts nothing.

The module hooks the JDK's internal `sun.net.httpserver` classes. On a JDK that changes a method the module hooks, the module switches itself off with the kind `HOOK_UNMATCHED`. A JDK that renames a hooked class goes unseen, since the module then matches nothing.

## Route bridge for OpenTelemetry

Set `otelBridgeEnabled=true` to also count the route OpenTelemetry's HTTP server instrumentation resolved, for a framework the modules above do not cover. The bridge hooks OpenTelemetry's internal classes, so whether it works depends on the OpenTelemetry version, not on your framework. The [table](#supported-frameworks) lists the tested versions. It works with the OpenTelemetry Java agent and with the `opentelemetry-instrumentation-api` library in your application.

The bridge counts at span end, after the handler ran, so a request that threw still counts. The template is OpenTelemetry's `http.route`, and the verb is the request method. A span with no route counts nothing. Every bridge endpoint is discovered at dispatch, so the bridge never shows an endpoint as never called.

The bridge skips a route when a framework module already holds the same identity, holds the same template with the verb `*`, or holds the `GET` endpoint of a template a `HEAD` request names. If that module is switched off, those requests go uncounted. To match the modules' templates, the bridge reads OpenTelemetry's route as follows:

- A Vert.x spelling, where a segment is only `:name` or `:name::type`, reads as `{name}`. Any other colon stays.
- OpenTelemetry's `/*.do` reads as the `servlet` module's extension pattern `*.do`, and its `/*` and `/` read as the default mapping `/`. A real path mapping `/*` is a path template, which the `servlet` module declares as `/*`, and matches as one.
- The application root, and for JAX-RS the servlet path, that OpenTelemetry put in front of the route is removed. A Spring application under the context path `/shop` reads as `/orders`, and a Jersey resource at `/users` behind a servlet at `/api/*` reads as `/users`. This covers OpenTelemetry's servlet instrumentation, its JAX-RS instrumentation for Jersey, CXF and RESTEasy, and its Spring MVC library instrumentation.

These routes are counted by both the bridge and a framework module, under two endpoints:

- A route whose prefix the bridge does not remove, including the base path the Spring WebFlux library instrumentation adds.
- A Vert.x Web route at `/files/*`, which OpenTelemetry reports as `/files`.
- A Vert.x Web route under a parameterised mount, which OpenTelemetry reports with the matched values, so each value becomes a bridge endpoint of its own.
- A Vert.x Web route with a colon inside a segment, such as `/:from-:to`.

A matched value in a bridge route is sent to Otherlode as part of that route. See [what the agent sends](data-sent#endpoint-routes).

## The handler

An endpoint names the method that serves it where the agent can find one. Otherlode and the testkit join the endpoint to that method's probe, so a method that never ran lists the endpoints it would serve. Counting does not depend on the join.

| Module | What the endpoint names |
|---|---|
| Spring, annotated | The class that declares the method (the user class, not a CGLIB proxy), the method and its descriptor. A mapping inherited from a base controller names the base class. |
| Spring, URL-mapped | The handler's class only, with no method. |
| Spring, functional | `handle` and the class that declares it. |
| Javalin, named handler class | `handle` and the class that declares it. |
| Javalin, lambda or method reference | The method the lambda calls, or the method the reference names. |
| Javalin, `CrudHandler` | The handler's method for the route: `getOne`, `getAll`, `create`, `update` or `delete`, with the class that declares it. |
| Javalin, `SseHandler`, `VueComponent` and any `VueHandler` that does not override `handle` | Nothing. |
| Micronaut 3, 4 and 5 | The class that declares the method Micronaut runs, the method and its descriptor, as the class file has them. A method a controller inherits from a base class or an interface names that base class or interface, a controller with AOP advice names the controller and not Micronaut's generated subclass, and a Kotlin `suspend` function has the continuation as its last parameter and `Object` as its return type. |
| Vert.x Web | The route's last context handler that is not one of Vert.x Web's priority handlers (platform, security policy, body, multi-tenant, authentication, input trust and authorization): `handle` and the class that declares it, or the method a lambda or method reference calls. A `blockingHandler` names the handler it wraps. Nothing for a `respond(...)` route, for a handler that is a class of Vert.x Web's own, such as `StaticHandler`, or for one a library wraps before Vert.x sees it: the RxJava and Mutiny APIs, and the OpenTelemetry Java agent when it is attached after this agent. |
| Ktor | The handler body's class and `invokeSuspend`. A handler class with no `invokeSuspend` names the class alone. |
| JAX-RS | The concrete class, the method and its descriptor. |
| JDK `HttpServer` | `handle` and the class that declares it. |
| OpenTelemetry bridge | Nothing. |

A Spring mapping registered under a bean name whose type cannot be resolved is an endpoint with no handler, and so is any endpoint the agent finds at dispatch with no registration to read. A functional route written in Spring's Kotlin DSL, `router { ... }` or `coRouter { ... }`, names no handler, and is still declared and counted.

Once an endpoint names a method, a later registration for the same identity does not replace it. If two handlers register the same verb and template, the first one with a method wins. An endpoint that names only a class gains a method when one is learned.

### Lambdas and method references

A Java or Kotlin lambda or method reference passed as a handler is a hidden class with no stable name. For the handler types the modules take, the agent names the method the lambda calls instead: `HttpHandler` for the JDK `HttpServer`, `HandlerFunction` for Spring functional routes, `Handler` for Javalin and `Handler` for Vert.x Web. The agent learns that method from the JDK's lambda factory as it creates each lambda class. The reported descriptor is the method's real one, with the values the lambda captured first.

A lambda gets no join in these cases:

- Its class was created before the agent started watching the lambda factory.
- The agent could not hook the lambda factory on this JVM. It logs one WARNING that starts `otherlode: handler lambdas and method references will not be named on this JVM`, and nothing else is affected.
- The lambda is for a subinterface of the handler type.
- The lambda is a reference to an abstract method, because which method runs depends on the receiver.
- The method's own class is hidden.

Ktor handlers compile to named classes, so they need none of this.

When a compiler generates a pass-through between the framework and your function, the agent follows it to the one function it forwards to. This covers a Kotlin function reference compiled with class-based SAM conversion and a Scala `$adapted` forwarder, for the handler types above. The join then names your function, which has a method probe.

## Modules that switch themselves off

A module does not check its framework's version when it installs. It switches itself off for the rest of the process when:

- its advice throws for any reason, including a linkage error against a framework version it does not match;
- one of its hooks matches no method on the framework class it hooks;
- a framework class it hooks fails to weave, including when another agent retransforms a class the module already wove, or a debugger redefines it, and the module cannot weave it again;
- the endpoint modules together reported 4,096 registrations, calls and route reads before the agent finished installing, and this module reported one more. The reason is `endpoint replay buffer overflow: records were dropped before a resolver was installed`.

It logs one WARNING:

```text
otherlode: endpoint module <name> disabled itself: <reason>
```

When the replay buffer fills, the agent also logs this WARNING once:

```text
otherlode: endpoint replay buffer is full (4096); dropping new endpoint records until a resolver is installed
```

For a linkage error the reason is the error with its message, which names the framework class or member that changed, such as `java.lang.NoSuchMethodError: ...getHandler...`. For any other exception it is the exception's class name alone. A servlet mapping outside the forms the servlet specification defines is reported with the mapping.

The agent sends the module's name, the reason and a kind to Otherlode with the next manifest:

| Kind | Cause |
|---|---|
| `LINKAGE_ERROR` | A linkage error, wherever it was caught, or a servlet container that does not implement Servlet 4.0. |
| `ADVICE_FAILED` | Any other throw from the module's advice, or a value it cannot use, such as a servlet mapping with no match type. |
| `TRANSFORM_FAILED` | The module's own transform threw, a framework class it hooks failed to weave, or that class's loader cannot see `dev.otherlode.bootstrap`. |
| `ROUTE_WALK_FAILED` | Reading a framework's routes failed. |
| `HOOK_UNMATCHED` | A hook matched no method. |
| `CONFIGURED_OFF` | Your configuration kept the module from installing. |
| `ROUTES_NOT_COUNTED` | A Micronaut runtime constructs no dispatch point the module counts at. |
| `DISABLED_ENDPOINT_MODULE_KIND_UNSPECIFIED` | The module lost records before the agent finished installing. Its reason says so. |

A module that [`disabledEndpointModules`](configuration#disabledendpointmodules) or `endpointsEnabled=false` keeps from installing is reported with the kind `CONFIGURED_OFF`, whether or not its framework is present, and logs no WARNING. A Micronaut module with the kind `ROUTES_NOT_COUNTED` has declared no endpoint and logs the WARNING above.

A module that switches off stops counting and retracts nothing. The endpoints it already declared stay in the list, and their counts stop. [Never called](/docs/server/findings#never-called) says how Otherlode reads them. The testkit's `disabledEndpointModules()` lists the disabled modules, `neverCalled()` leaves out an endpoint when every instance that knows it disabled its module, and its endpoint queries name a disabled module in the error they throw for an unknown endpoint.

The JAX-RS module is an exception: it hooks your own resource classes, so a class that fails to weave runs from its original bytes, the agent logs a WARNING naming it, and the module stays on for the other classes. It does switch off when another agent retransforms a class it already wove, or a debugger redefines it, and it cannot weave the class again, unless that class's first weave failed as well.

A framework class that was renamed is not caught. The module never matches it, which reads as the framework being absent. See [troubleshooting](troubleshooting).

## Frameworks with no module

A service whose web framework has no module reports no endpoints. So that you can tell this from a service that serves none, the agent keeps a fixed list of framework markers: a class, or a set of classes, that the JVM defines only once a framework's own routing is set up. When every class of a marker has been defined, the agent reports the marker's id and the classes that matched on the next manifest, once per run. It reads the names of classes the JVM has already defined and never loads one to look. It looks on every flush, and on the final flush.

A marker is an observation, not a claim that the framework is uncovered, since a module may count some of its routes. Markers are reported whatever else runs: a service with Spring MVC and a gRPC server reports both the endpoints and the gRPC marker, and a test run reports markers too.

| Marker id | Class that loads | Claim |
|---|---|---|
| `grpc-server` | `io.grpc.internal.ServerImpl$ServerListenerImpl` | A grpc-java server started, an in-process server included. A client alone does not load it. Armeria serving gRPC does not use it |
| `armeria-server` | `com.linecorp.armeria.server.HttpServerPipelineConfigurator` | An Armeria server started. A client alone does not load it |
| `undertow-routing-handler` | `io.undertow.server.RoutingHandler` | Undertow's own `RoutingHandler` configured, whether or not the server started. A server answering through another handler does not load it. WildFly 42 loads it at boot for JNDI over HTTP, served under `/wildfly-services` by default, so every WildFly service reports this marker, even with the `http-invoker` removed |
| `javalin-5` | `io.javalin.jetty.JettyServer$start$wsAndHttpHandler$1` and `io.javalin.http.servlet.JavalinServlet` | Javalin 5's own Jetty server started. Javalin 4 loads the first class but not the second, and no Javalin release a module covers has the first. A Javalin 5 application deployed as a servlet in another container loads no marker |
| `play-server` | `play.core.server.PekkoHttpServer` (Play 3), `play.core.server.AkkaHttpServer` (Play 2.8 and 2.9) or `play.core.server.NettyServer` (Play 2.8 to 3.0, with the Netty backend) | A Play server started. Play's other classes, a router built without a server and the server provider alone do not load it. Any one of the three classes completes the marker |
| `helidon-webserver` | `io.helidon.webserver.LoomServer$ListenerFuture` | A Helidon 4 `WebServer` started. Helidon 3 and earlier have no marker. A Helidon client, and a server that was built and not started, do not load it. Helidon MP starts the same server, so it loads this too, and its JAX-RS resources are counted by the JAX-RS module |
| `struts2-dispatcher` | `org.apache.struts2.dispatcher.ExecuteOperations` (Struts 2.5 and later) or `org.apache.struts2.dispatcher.ng.ExecuteOperations` (Struts 2.3) | A Struts 2 dispatcher filter or servlet started in a servlet container. Only `StrutsPrepareAndExecuteFilter`, `StrutsExecuteFilter` and `StrutsServlet` create it, in their `init`. Struts 2.3's deprecated `FilterDispatcher` creates neither, so an application served through it reports no marker |
| `grails-url-mappings` | `org.grails.web.servlet.mvc.GrailsDispatcherServlet` and Spring Boot's `ServletWebServerApplicationContext` (in `org.springframework.boot.web.servlet.context`, or `org.springframework.boot.web.server.servlet.context` on Spring Boot 4), or, for a Spring Boot 4 application deployed as a WAR, `SpringBootServletInitializer$WarDeploymentApplicationContextFactory` | Grails created the dispatcher servlet for its URL-mapped controllers in a servlet web server context, whether or not the server accepts requests. A Grails process with no web server context, such as a command or a unit test, does not complete the set. Grails' `@RequestMapping` controllers are counted by the Spring MVC module |
| `webflux-router-without-dispatcher` | `org.springframework.web.reactive.function.server.RouterFunctions$RouterFunctionWebHandler` | A Spring WebFlux router turned into a handler by `RouterFunctions.toHttpHandler` or `toWebHandler`, whether or not it was served. A router served by a `DispatcherHandler` does not load it. A test that binds `WebTestClient` to a router function loads it |

The project's tests run each marker in at least three JVMs with `-Xlog:class+load`: one that uses only the framework's client side, or the framework without its routing, one that configures routing without starting it, and one that serves a request. They run against these releases: grpc-java 1.30.2 to 1.85.0 (five releases), Armeria 1.0.0 to 1.42.0 (five releases), Undertow 2.2.37, 2.3.26 and 2.4.1, Javalin 5.0.0, 5.3.2 and 5.6.5 (and 4.6.8, which must not complete the marker), Spring Framework 5.3.39, 6.2.19 and 7.0.9, Play 3.0.0 and 3.0.12 with Pekko HTTP, Play 2.8.0, 2.8.22, 2.9.0 and 2.9.12 with Akka HTTP, Play 2.8.22, 2.9.12 and 3.0.12 with Netty, Helidon 4.0.0, 4.2.7 and 4.5.5, Struts 2.3.37, 2.5.33, 6.0.0, 6.12.0, 7.0.0 and 7.4.0 in embedded Tomcat, and Grails 6.2.3, 7.0.0, 7.2.4 and 8.0.0 (7.0.0, 7.2.4 and 8.0.0 also deployed as a WAR in Tomcat, and each also run with no web server, which must not complete the marker).

## Settings

| Option | Default | Effect |
|---|---|---|
| `endpointsEnabled` | `true` | Turns every framework module, and the route bridge with them, off at once when `false`. The agent logs `otherlode: endpointsEnabled=false, no framework's endpoints will be instrumented` at INFO, and reports each module it would have installed as disabled with the kind `CONFIGURED_OFF`. The method and branch probes are unaffected. |
| `disabledEndpointModules` | None | Module names, separated by `;`. The agent installs none of them and reports each as disabled with the kind `CONFIGURED_OFF`, except `otel` while `otelBridgeEnabled` is off. |
| `otelBridgeEnabled` | `false` | Turns the OpenTelemetry route bridge on. |

The [configuration options](configuration) page gives the system property and environment variable names for all three.

## What is not covered

- Javalin 5 has no module. With `otelBridgeEnabled`, its routes are counted as OpenTelemetry resolves them, discovered at dispatch only, so none reads as never called. The agent reports the `javalin-5` [marker](#frameworks-with-no-module). A Javalin 5 application deployed as a WAR loads no marker, and its `JavalinServlet` is counted by the `servlet` module as one `*` endpoint on its mapping. The same holds for a Ktor 1 `ServletApplicationEngine`.
- A Spring WebFlux router run through `RouterFunctions.toHttpHandler` or `toWebHandler`, with no `DispatcherHandler`, is not declared, and only its `resources(pattern, location)` routes are counted, found at dispatch. The agent reports the `webflux-router-without-dispatcher` [marker](#frameworks-with-no-module).
- JSP pages are not counted. They run inside the container's JSP servlet, which the servlet module leaves out.
- The SOAP traffic of Apache CXF's `CXFServlet` is not counted. Its JAX-RS requests are counted by the JAX-RS module.
- A container that implements a servlet API older than 4.0, such as Tomcat 8.5 or Jetty 9.4, has no servlet counting. The `servlet` module is reported as disabled with the kind `LINKAGE_ERROR` at the first request, and the application is served as before.
- Requests that never reach the hooked framework are not counted. A servlet, filter or Tomcat valve that answers first is invisible to the Spring module, for example.
- The agent reads no OpenAPI document and does no static analysis of call sites to find endpoints.
- A route that a router builds at runtime by any means other than the registration points above is found only when a request reaches it.
- JAX-RS runtimes other than Jersey, and Ktor releases other than the ones in the table, have no test run against them.
