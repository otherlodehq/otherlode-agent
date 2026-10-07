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
| Ktor 2 | `ktor-2` | 2.3.13 | Once Ktor chose the route, before its handlers run |
| Ktor 3 | `ktor-3` | 3.0.3, 3.5.2 | Once Ktor chose the route, before its handlers run |
| JAX-RS, `javax.ws.rs` | `jaxrs` | Jersey 2.48 with `javax.ws.rs-api` 2.1.1 | Entry of the resource method |
| JAX-RS, `jakarta.ws.rs` | `jaxrs` | Jersey 3.1.12 with `jakarta.ws.rs-api` 3.1.0 | Entry of the resource method |
| JDK `com.sun.net.httpserver.HttpServer` | `jdk-httpserver` | JDK 21, the test toolchain | Once the server found the context, before the handler runs |
| Any framework OpenTelemetry instruments | `otel` | `opentelemetry-instrumentation-api` 2.31.1, and the OpenTelemetry Java agent 2.31.1's relocated copy. It compiles against 2.0.0 | Span end, after the handler ran. Off by default |

The module name is what the agent logs, what a collector sees as the endpoint's framework, and what appears in the list of [disabled modules](#modules-that-switch-themselves-off).

One Spring module covers Spring Framework 5.3 through 7 and both the `javax` and `jakarta` servlet namespaces. The project's demo runs it inside a Spring Boot 4.1.1 fat jar. The Ktor modules are separate because Ktor 3 replaced `Route` with `RoutingNode`. The JAX-RS module reads annotations from either namespace.

Spring WebFlux, Javalin, Vert.x and Micronaut have no module. See [what is not covered](#what-is-not-covered).

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
| Ktor's optional marker stays | `/{id?}` | `/{id?}` |
| A `*` inside a segment stays | `/files/*.txt` | `/files/*.txt` |
| Case is kept | `/Orders` | `/Orders` |

The verb is upper case. An endpoint with no verb constraint has the verb `*`. The framework's own spelling travels beside the template for display. The testkit calls it `verbatimTemplate`.

The servlet context path is not part of the template. Spring reports the application-relative template, so a Spring Boot application with `server.servlet.context-path=/shop` still reports `/orders`. JAX-RS leaves out `@ApplicationPath`. The JDK `HttpServer` is the one exception, because a context path is its whole template.

A framework mapping that lists several paths or verbs gives one endpoint per combination. `@RequestMapping(path = ["/a", "/b"], method = [GET, POST])` is four endpoints.

Two registrations that name the same verb and template are one endpoint with one count, even from two servers in the same JVM or from two frameworks. The numeric endpoint id the agent assigns is only meaningful inside one run, so a collector identifies an endpoint across instances by its verb and template. The framework recorded for the endpoint is the one that registered it first. Two routes that differ only in a part the agent does not read merge for the same reason. Each module below says what it does not read.

## Declared and discovered

The agent learns of an endpoint in one of two ways, and the manifest records which.

An endpoint is declared when the framework registers it. This is what makes "never called" computable: the endpoint exists, a framework would serve it, and no request matched it.

An endpoint is discovered by dispatch when a request matches one that no registration hook reported. The agent creates it on the spot and counts the request, so no hit is dropped. A discovered endpoint has been called by definition. If a registration for the same verb and template arrives later, the endpoint changes to declared.

Declared endpoints include the ones your framework registers for itself. Spring's static-resource mappings and Spring Boot Actuator's operations appear in the list, with their handler classes where the agent can name them. Dropping an endpoint because its handler cannot be named would hide it, so the agent reports it unjoined instead.

The route bridge only ever discovers. OpenTelemetry learns a route when a request ends on it, so there is nothing to declare from.

## Spring MVC

Annotated controllers are declared when Spring builds its handler-method table, and when code registers a mapping directly with `registerMapping`. Actuator registers every operation that way. A request is counted against the pattern and verb Spring matched, using its best match when more than one pattern fits. A mapping with no verb is `*`.

URL-mapped handlers are `SimpleUrlHandlerMapping`, `BeanNameUrlHandlerMapping` and Spring Boot's static-resource and webjars mappings. Their endpoints always have the verb `*`. Spring falls back to the root handler or a `/*` handler when no registered path matches, and the agent counts that fallback against `/` or `/*`. It does not create an endpoint per requested URL.

Functional routes (`RouterFunction` beans) are declared by walking the router function once, when Spring first builds its route table. The walk reads these predicates:

- `method(...)` gives the verb.
- `path(...)` gives the template.
- `and` combines a path with a path, or a path with a verb.
- `or` declares each alternative.
- A nest's path is a prefix for every route inside it.

Header, parameter, path-extension and `version` predicates narrow nothing, and neither does a negated predicate, so routes that differ only by those merge into one endpoint. A route the walk cannot resolve to a path is not declared, and the agent logs this once at INFO: `a spring-webmvc functional route had no path predicate this walk could resolve; it is counted only if a path predicate matched on the way to it`. Such a route is counted at dispatch when a path predicate matched on the way to it. A route with no path predicate anywhere is not tracked at all. A `resources` route is found at dispatch, not declared. A CORS preflight request is not counted.

## Ktor

Ktor routes are declared when the routing tree is built. Each `get`, `post` or `handle` call that attaches a handler registers its route. A route node with no handler is not an endpoint. A request is counted once, when Ktor has chosen the winning route and before that route's handlers run. A request that matches no route counts nothing.

The agent builds the template by walking from the route up to the root. Constant segments, parameters (with any prefix or suffix text), optional parameters, tailcards and wildcards each add a segment. The verb comes from the nearest HTTP method selector, or is `*` when the route has none. Other selectors add nothing to the template, such as headers, content type, query parameters and host. Routes that differ only by those selectors merge into one endpoint.

## JAX-RS

JAX-RS has no registration call, so the agent reads the annotations when a resource class loads and declares the routes then. A request is counted when the resource method is entered, which a JAX-RS server does after it matches the request. A call made directly from your own code also counts.

A class is read when all of these hold:

- It is concrete, not abstract and not an interface.
- It, or a supertype, carries `@Path` or declares a method carrying `@Path` or a verb annotation (`@GET`, `@POST`, `@PUT`, `@DELETE`, `@HEAD`, `@OPTIONS`, `@PATCH`).
- Its class loader can see `jakarta.ws.rs.Path` or `javax.ws.rs.Path`.

A method becomes an endpoint when it has a verb or `@Path`, declared on itself or inherited as described below. A method with `@Path` and no verb is a sub-resource locator and is declared with the verb `*`. A custom annotation meta-annotated with `@HttpMethod` gives its value, upper-cased, as the verb. A class whose only routing annotation is such a custom verb, with no `@Path` anywhere, is not read.

The template is the class-level `@Path` joined to the method's `@Path`. A class with no class-level `@Path` in effect is not a root resource, so the agent declares nothing for it. That includes a sub-resource class a locator returns. Calls to it count on the locator's endpoint.

### Inheritance

The agent follows the JAX-RS annotation-inheritance rules for methods and adds one Jersey behaviour for classes.

- A method with no JAX-RS annotation of its own inherits the annotations of the method it overrides or implements. The search tries the superclass chain first, then the interfaces in declaration order. Any JAX-RS annotation on the method itself, even `@Produces`, turns inheritance off for that method.
- Only methods the concrete class declares are read. A method body inherited from a superclass without an override is not declared on the subclass.
- A class-level `@Path` on a superclass or interface is used only when Jersey is on the resource class's class loader. The specification does not inherit it and RESTEasy does not either. Without Jersey, the class declares nothing, and the agent logs once at INFO: `class-level @Path on a supertype of <class> is ignored, so it declares no endpoints`. The agent detects Jersey by the presence of `org/glassfish/jersey/server/model/Resource.class` on that loader. When Jersey resolves the class path, a superclass's `@Path` wins over an interface's.
- When two interfaces give a method different verbs or paths, the first in declaration order wins and the agent logs a WARNING once.

Only Jersey is tested. A resource served by RESTEasy, Apache CXF or another runtime is declared by the same annotation rules, which the specification defines. No test runs it.

A runtime-generated proxy of a resource class, such as a Spring CGLIB proxy, is skipped. The proxy calls the real method, so the real class counts.

## JDK HttpServer

Every `createContext` call registers an endpoint, with or without a handler. A later `setHandler` attaches the handler to the context. A context is a prefix match with no verb constraint, so the verb is `*` and the template is the context path exactly as registered. A request is counted when the server finds its context, before the handler runs. A request no context matches (the server's own 404) counts nothing.

The module hooks the JDK's internal `sun.net.httpserver` classes, which are not a supported API. On a JDK that changes them, the module can find nothing to hook.

## Route bridge for OpenTelemetry

Set `otelBridgeEnabled=true` to also count the route OpenTelemetry's HTTP server instrumentation resolved. Use it for a framework the modules above do not cover. It is off by default because it hooks OpenTelemetry's internal classes, so its correctness depends on the OpenTelemetry version and not on your framework.

The bridge works with the OpenTelemetry Java agent and with the `opentelemetry-instrumentation-api` library in your application. It counts at span end, because OpenTelemetry settles a request's route only then. So it counts after the handler ran, and a request that threw still counts. The route is OpenTelemetry's `http.route`, and the verb is the request method. A span with no route counts nothing.

The bridge never discovers an endpoint another module owns. It skips a route when a framework module already holds the same identity, when a framework holds the same template with the verb `*`, and when a `HEAD` request names a template whose `GET` endpoint a framework holds. Otherwise a request that a framework module counted would count twice. If the owning module is switched off, those requests go uncounted.

Two cases differ from the framework modules. OpenTelemetry's route includes the servlet context path, so a servlet application can report `/shop/orders` where the framework module reports `/orders`, and the two read as separate endpoints. A servlet-only application reports whatever OpenTelemetry reports, which may be a servlet mapping such as `/api/*`.

## The handler

An endpoint names the method that serves it where the framework lets the agent find one. A consumer joins the endpoint to that method's probe, so a method that never ran can list the endpoints it would serve. The join is a label on the endpoint. Counting does not depend on it.

| Module | What the endpoint names |
|---|---|
| Spring, annotated | The class that declares the method (the user class, not a CGLIB proxy), the method and its descriptor. A mapping inherited from a base controller names the base class. |
| Spring, URL-mapped | The handler's class only, with no method. |
| Spring, functional | `handle` and the class that declares it. |
| Ktor | The handler body's class and `invokeSuspend`. A handler class with no `invokeSuspend` names the class alone. |
| JAX-RS | The concrete class, the method and its descriptor. |
| JDK `HttpServer` | `handle` and the class that declares it. |
| OpenTelemetry bridge | Nothing. |

The agent skips the join rather than guess. A Spring mapping registered under a bean name whose type cannot be resolved is still an endpoint, with no handler. The same holds for any endpoint the agent finds at dispatch with no registration to read.

Once an endpoint names a method, a later registration for the same identity does not replace it. If two handlers register the same verb and template, the first one with a method wins. An endpoint that names only a class gains a method when one is learned.

### Lambdas and method references

A Java or Kotlin lambda or method reference passed as a handler becomes a hidden class with no stable name, so its class name joins to nothing. For the handler types the modules take, the agent instead names the method the lambda calls. These are `HttpHandler` for the JDK `HttpServer` and `HandlerFunction` for Spring functional routes. It does this by watching the JDK's lambda factory as it creates each such class, then reading the answer back when the framework hands the lambda over.

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

Each module is built against one version of its framework and does not check the version when it installs. If its advice throws for any reason, including a linkage error against a framework version it does not match, the module switches itself off for the rest of the process. It logs one WARNING:

```text
otherlode: endpoint module ktor-3 disabled itself: <reason>
```

The agent sends the module's name and the reason to the collector with the next manifest. A collector can then tell "this service has no endpoints" from "this service's endpoints were not instrumented". The testkit exposes it as `disabledEndpointModules()`, and its endpoint queries mention a disabled module in the error they throw for an unknown endpoint.

A module that switches off stops counting but does not retract. The endpoints it already declared stay in the list, and their counts stop, so they read as never called. The disabled-modules list is the only signal for that. Treat any endpoint finding from a service with a disabled module as unreliable for that framework.

Two related failures do not disable a module. If a module cannot transform one class, that class runs without endpoint tracking and the agent logs a WARNING naming it. If another agent retransforms a class the module already wove and the module cannot weave again, the agent logs one WARNING for that module and that class goes untracked until it is next transformed.

A framework version that no longer has the method a module hooks does not fail. The advice finds nothing to attach to, and the service reports no endpoints for that framework with no disabled module to show for it. See [troubleshooting](troubleshooting).

## Settings

| Option | Default | Effect |
|---|---|---|
| `endpointsEnabled` | `true` | Turns every framework module, and the route bridge with them, off at once when `false`. The agent logs `endpointsEnabled=false, no framework's endpoints will be instrumented` at INFO. The method and branch probes are unaffected. |
| `otelBridgeEnabled` | `false` | Turns the OpenTelemetry route bridge on. |

There are no per-framework switches. The [configuration options](configuration) page gives the system property and environment variable names for both.

## What is not covered

- Spring WebFlux, Javalin, Vert.x and Micronaut have no module. Counting their endpoints needs a module per framework. For them, `otelBridgeEnabled` counts what OpenTelemetry resolves, discovered at dispatch only, so "never called" cannot be computed.
- Requests that never reach the hooked framework are not counted. A servlet, filter or Tomcat valve that answers first is invisible to the Spring module, for example.
- The agent reads no OpenAPI document and does no static analysis of call sites to find endpoints.
- A route that a router builds at runtime by any means other than the registration points above is found only when a request reaches it.
- JAX-RS runtimes other than Jersey, and Ktor releases other than the ones in the table, have no test run against them.
