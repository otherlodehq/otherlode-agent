---
status: accepted
---

# A method inherits a callback annotation where its framework does

Decided on 2026-10-10 in a grilling session with Luke. It extends ADR 0064, which read only the annotations on the method that runs. A framework annotation often sits on an interface or superclass method in the adopter's own code: a Spring controller implements an OpenAPI-generator `XxxApi` interface that carries `@GetMapping`, a JAX-RS resource implements an annotated interface, a Temporal workflow implements its `@WorkflowInterface`. The overriding method carried nothing, its supertype was in scope so no override was out of scope, and a never-hit handler read *uncalled*.

A METHOD probe's outside caller can come from a method it overrides. For a probed method, the agent visits its supertypes in the override walk's order (the superclass and its supertypes, then each interface in declaration order with its supertypes), in scope or out, and takes the first supertype method of the same name and descriptor (a package-private one only from the same package; a bridge passes its supertype to its single same-class callee, as in ADR 0064) that carries a callback annotation its framework honours from that kind of supertype. The label is `CALLBACK_ANNOTATION` naming that annotation, as written on the supertype method. No wire field says where it came from.

Precedence: the method's own callback annotation, then an inherited one, then an out-of-scope override (`OVERRIDES_METHOD`). A method that overrides an out-of-scope base class's `@EventListener` method therefore names `@EventListener`, not the base type.

## Inheritance is per family

The JDK never inherits method annotations; a framework honours a supertype method's annotation only if its own discovery walks the hierarchy, and frameworks disagree. Each built-in name carries the rule its framework follows, read from its source on 2026-10-10 (Spring Framework 7.0.9, Spring Kafka and AMQP 4.1.1, Spring GraphQL 2.0.5, Spring Boot 4.1.1, Jersey 4.0.3, RESTEasy 7.0.5, Weld 7.0.0, Micronaut 4.10.31, Quarkus 3.40.1, Guava 33.7.2, Temporal 1.40.0, Axon 4.13.2 and 5.3.3, and the Jakarta specifications):

| Rule | Annotations |
|---|---|
| From interface and superclass methods | Spring `@EventListener`, `@TransactionalEventListener`, the `org.springframework.web.bind.annotation` mappings with `@ExceptionHandler`, `@ModelAttribute` and `@InitBinder`, `@MessageMapping`, `@MessageExceptionHandler`, `@SubscribeMapping` (found through `MethodIntrospector` with a `TYPE_HIERARCHY` search); Kafka `@KafkaListener(s)`, `@KafkaHandler`; Rabbit `@RabbitListener(s)`, `@RabbitHandler`; the Spring GraphQL mappings; JAX-RS verbs, `@Path` and `@HttpMethod`, with the rule below; Micronaut `@HttpMethodMapping` (declared `@Inherited`) and `@EventListener`; Guava `@Subscribe`; Axon's `MessageHandler` in both packages |
| From superclass methods and interface default methods, not abstract interface methods | Spring `@Bean` (`ConfigurationClassParser` skips abstract interface methods and resolves a factory method by name) |
| From interface methods only, and not on the method itself | Temporal `@WorkflowMethod`, `@SignalMethod`, `@QueryMethod`, `@UpdateMethod`, `@UpdateValidatorMethod` (Temporal reads them from interface methods only, on any interface the class implements at any depth) |
| Never inherited | Spring `@Scheduled(s)`, `@JmsListener(s)` and the actuator operations (direct lookups); `@Inject`; `@PostConstruct`, `@PreDestroy`, `@AroundInvoke`, `@AroundTimeout`, the JPA entity callbacks (the Interceptors and Persistence specifications say an override suppresses them); the WebSocket `@On*` annotations (WebSocket 2.2 §4.8); EJB `@Schedule(s)` and `@Timeout`; Micronaut `@Scheduled(s)`; Quarkus `@Scheduled` and `@Startup`; Axon 4 `@StartHandler` and `@ShutdownHandler`; Axon 5 `@EntityCreator` and `@EventCriteriaBuilder`; Temporal `@TemporalOperation` |

A callback that runs at every start is "never" whatever its container does. Where a container honours an inherited one, as Spring does for `@PostConstruct` through virtual dispatch, the override ran and has hits, so it is never a root; a never-hit override is one the container did not call.

A composed annotation on a supertype method takes the rule of the listed annotation it reaches. An annotation the adopter names in `callbackAnnotations` inherits from interface methods, abstract or default, and from superclass methods, at either retention, since an in-house dispatcher almost always finds handlers through a hierarchy search.

**JAX-RS follows its specification** (REST 4.0 §3.6): a method that carries any `jakarta.ws.rs` or `javax.ws.rs` annotation, on itself or its parameters, inherits no JAX-RS annotation. It is labelled by its own annotations or not at all, as ADR 0020 reads it for the endpoint tier.

**Temporal activities are marked by their interface.** Every non-static, non-synthetic method of an `@ActivityInterface` is an activity, annotated or not, and an unannotated super-interface's methods pass down to the nearest annotated descendant (`POJOActivityInterfaceMetadata`). A method that implements a method declared by an interface annotated `@ActivityInterface`, or by an unannotated super-interface of one, is labelled `io.temporal.activity.ActivityInterface`. This is the only rule read from a type's annotation; an adopter's named annotation still counts on methods only.

**Axon is listed by its meta-annotation.** Every Axon handler annotation is meta-annotated `MessageHandler` (`org.axonframework.messaging.annotation` in Axon 4, `org.axonframework.messaging.core.annotation` in Axon 5), and Axon finds handlers by walking meta-annotations to any depth. Listing `MessageHandler` in both packages finds `@CommandHandler`, `@EventHandler`, `@QueryHandler`, `@EventSourcingHandler`, `@SagaEventHandler`, `@DeadlineHandler`, `@ResetHandler`, the interceptor annotations and any an adopter composes, the way `@HttpMethod` finds every JAX-RS verb.

## Considered options

- **Any callback annotation on an overridden method counts.** Rejected: Spring `@Scheduled`, `@JmsListener` and the CDI and JPA lifecycle callbacks would label methods their frameworks never call, which hides dead code, the reason ADR 0064 rejected counting any annotation.
- **Reading a bridge method's annotations instead of the supertype's.** Rejected: javac copies the implementing method's annotations onto its bridge, never the supertype's (`TransTypes.addBridge`), kotlinc copies none onto a generic bridge, and the OpenAPI-generator shape erases identically and has no bridge at all.
- **Sharing ADR 0020's JAX-RS code.** Not possible: it works on ByteBuddy type descriptions in the `endpoints-jaxrs` module, while the outside-caller walk reads class-file headers. The order and the specification's rule carry over; the code does not.
- **A wire field naming the supertype.** Rejected: "called from outside scope: `@GetMapping`" is enough to act on, and a field needs a collector bindings bump and server work.
- **Temporal and Axon left to `callbackAnnotations`.** Rejected: the option counts on methods only, so it cannot express a Temporal activity, which needs no annotation on its method, or a workflow annotation that counts only on an interface.

## Consequences

- The override walk's type headers keep each overridable method's annotations and whether it is abstract, and each type's own annotations, for the types they are read for. Headers of `java.*` types carry no callback annotation.
- A method whose own label was `OVERRIDES_METHOD` reads `CALLBACK_ANNOTATION` when the out-of-scope method it overrides carries an annotation its framework honours there.
- An in-scope interface's annotated default method, or Kotlin `$DefaultImpls` body, is labelled by its own annotation.
- The static baseline's declared methods carry no outside caller.
- A listed annotation's own rule decides, never what it carries: Spring's `@JmsListener` is meta-annotated `@MessageMapping`, which passes down, and `@JmsListener` still never does.
- Matching is by name and descriptor, so a generic method specialised in an intermediate class is missed: `Base implements Api<String>` with `handle(String)`, and `Leaf extends Base` overriding `handle(String)`, gives `Leaf` no bridge, and `Api.handle(Object)` does not match. Spring resolves the generics and finds it; the `OVERRIDES_METHOD` lookup has the same gap.
- A JAX-RS intermediate superclass method that carries only a non-listed JAX-RS annotation, such as `@Produces`, ends Jersey's search with no resource method. The walk passes over it and can inherit `@GET` from an interface beyond it, a label Jersey would not honour.
- The walk's order puts a class's superclass chain before its interfaces, as Jersey does. Spring visits a class's interfaces before its superclass, which changes only which annotation is named when both carry one that passes down.
- An annotation the adopter names inherits from any supertype method, so naming a built-in annotation whose rule is never makes it pass down, and naming a JAX-RS annotation takes it past the REST 4.0 §3.6 block.
