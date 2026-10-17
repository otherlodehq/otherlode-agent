# Changelog

All notable changes to the agent and the testkit, which share a version, are recorded here in the
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) format.

`scripts/release.sh` and the release workflow both require a `## [X.Y.Z]` section with a body for each release.
Keep link reference definitions (`[0.1.0]: https://...`) at the bottom of the file: the first one ends
the section being read.

## [Unreleased]

### Added

- Endpoint counting for Javalin, as the modules `javalin-6` (6.1 and later) and `javalin-7`. Each declares the HTTP routes Javalin registers, including `ApiBuilder` routes, `CrudHandler` routes and the OPTIONS routes of `HttpAllowedMethodsPlugin`, and counts a request once it reaches its handler, after the before-handlers. A `CrudHandler`'s five endpoints join to its methods. Javalin 6.0 reports `javalin-6` as disabled with the kind `LINKAGE_ERROR`.
- Endpoint counting for Spring WebFlux, as the module `spring-webflux`, on Spring Framework 5.3, 6 and 7. It declares annotated controllers, URL-mapped handlers, functional routes and `resources` routes, and counts a request once, with a CORS preflight as no call.
- A Spring functional route written in the Kotlin DSL (`router { }`, `coRouter { }`) names no handler, on Spring MVC and Spring WebFlux.
- `disabledEndpointModules` keeps single endpoint modules from installing, by name, separated by `;`.
- A module the configuration keeps off, by `disabledEndpointModules` or by `endpointsEnabled=false`, is reported as disabled with the kind `CONFIGURED_OFF`, in the wire format and in the testkit.
