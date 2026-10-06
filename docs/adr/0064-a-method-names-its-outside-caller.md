---
status: accepted
---

# A method names its outside caller, from its supertypes and a list of callback annotations

Decided on 2026-10-06 in a grilling session with Luke, as the first open bullet under `STATUS.md`'s pre-release checklist item 2. It closes the gap ADR 0024's consequences left: a call that leaves scope and comes back in shows as no edge, so a framework calling an adopter's method made a never-hit method an uncalled root.

The agent tells the collector why code outside scope may call a method. `ProbeLocation` carries an `OutsideCaller` on a METHOD probe, with a kind and a type:

- `OVERRIDE`: the method overrides or implements a method that an out-of-scope type declares. The type is that declaring type, dotted, such as `java.lang.Runnable`, `org.springframework.context.ApplicationListener` or `java.lang.Object`.
- `ANNOTATION`: the method, or one of its parameters, carries an annotation from the agent's list of callback annotations, directly or through meta-annotations at any depth. The type is the annotation as written on the method, so a composed `@MyAppListener` is named, not the `@EventListener` it carries.

A method has at most one outside caller. An annotation wins over an override, since it says more about who calls the method, and between two annotations the first in class-file order wins. The collector labels a never-hit method root with no in-scope caller and an outside caller *called from outside scope*, not *uncalled*. It keeps the root's test callers, and it takes precedence over *called only by tests*: "only" is a claim the evidence does not support when a framework may call the method. Nothing else changes. The method stays never hit, it still joins the cluster of a never-hit caller, and every count stays as it is.

The override walk reads every supertype at any depth through the loader's class files, using the shared byte cache, with a method table (name, descriptor, access) cached per type. A method matches a declaration by name and descriptor when the declaration is neither static nor private nor a constructor. A walk through in-scope supertypes continues to their out-of-scope ones, so `B : MyHandler` with `MyHandler : Runnable` marks `B.run`. A generic override overrides only through its bridge, so the mark follows a same-class bridge to the method it calls: `compareTo(Foo)` is marked through `compareTo(Object)`. A Kotlin `$DefaultImpls` method is marked only when its interface's method itself overrides an out-of-scope one. A supertype whose class file cannot be read stops that branch of the walk and marks nothing.

The callback annotations are agent data, a list of type names checked against current releases on 2026-10-06. They cover Spring's listeners, scheduling, `@Bean`, web, messaging, GraphQL and actuator mappings, the Kafka, Rabbit and JMS listeners, Jakarta and `javax` lifecycle, JAX-RS, EJB, CDI, WebSocket, JPA, interceptor and `@Inject` callbacks, Micronaut, Quarkus and Guava's `@Subscribe`, and the container each repeatable one uses when repeated. Composed annotations the frameworks ship are listed directly, so the meta-annotation walk is needed only for annotations an adopter composes. `@ModelAttribute` counts only on the method, and `@Inject` only on a method.

## Considered options

- **The collector guesses from `ClassLocation`'s supertypes.** Rejected: it knows a class implements `ApplicationListener` but has no out-of-scope method tables, so it would label a private helper on that class and miss a method inherited through an in-scope abstract class.
- **Recording out-of-scope callees, as ADR 0024 suggested.** Rejected: knowing that adopter code calls `executor.submit(...)` does not say whose `run` the framework later calls.
- **Any out-of-scope runtime annotation counts.** Rejected: a runtime `@Nullable` or `@Transactional` would hide real dead code.
- **`org.springframework.aot.hint.annotation.Reflective` as the signal.** Rejected: it also sits on `@Async`, which marks methods application code calls directly.
- **A marked method leaves its caller's cluster and roots its own.** Rejected: nothing called it from outside either, since it was never hit, and every never-hit `toString` reached only from dead code would split off a root of its own.
- **A bool, or overrides only with the annotation list later.** Rejected: a field added after release is stripped by every older collector with redaction on, and the run then makes no claim (collector ADR 0005). Building the general field and its first use now costs nothing on the wire.
- **An agent option for an adopter's own annotations.** Deferred, not rejected: it is additive and touches no wire field. Build it when an adopter asks or real reports show uncalled roots carrying in-house annotations.

## Consequences

- The static baseline's `DeclaredMethod` carries no outside caller. A never-loaded class's methods fold into its class finding, and the scan cannot read Spring or Jakarta supertypes inside a Boot fat jar, since it never opens `BOOT-INF/lib`.
- `java.*` supertypes are read too, through the bootstrap and platform loaders, which `SupertypeGuard` skips. Each type is read and parsed once per loader.
- A never-hit web handler is both a never-called endpoint and a root called from outside scope, so the two views agree.
- `otherlode-testkit` gains `RootKind.CALLED_FROM_OUTSIDE_SCOPE` and the outside caller on its method records, and the demo's stub collector applies the same rule. `otherlode-server` stores the field and adds the root kind.
