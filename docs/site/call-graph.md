---
title: Unreached code and the call graph
description: Why a list of never-hit methods is not enough, how call edges group unreached code into clusters, and what each kind of cluster root tells you about deleting it.
order: 70
---

A method with zero hits may have zero hits only because its one caller has zero hits too. Delete the callee and keep the caller, and you gain nothing. Delete the caller and keep the callee, and you leave dead code behind. So the agent also records which methods in your code each method refers to. Otherlode and the testkit join those references into a call graph and group never-hit methods into unreached clusters.

An unreached cluster is a root plus every never-hit method that the root reaches and whose in-scope callers are all inside the cluster. Deleting the root removes the whole cluster.

## What a call edge is

A call edge is one method's reference to another method, or to a class's static initialiser. The agent reads it from the caller's bytecode when the class loads and sends it once with the class. Nothing is counted per call, so edges add no cost to a request. An edge says that the caller's code can reach the callee, not that it did. The hit counts say what ran.

- **Only your code is in the graph.** The agent records an edge only when the callee is inside your [`includePackages`](configuration) scope. A call into the JDK, a framework or a library leaves no edge. The agent records those classes as [references](dependencies#what-counts-as-a-reference) to your dependencies, which never feed into clusters.
- **The callee is named as the bytecode names it.** The agent does not resolve which override a virtual call lands on. It sends each class's superclass and interfaces with the class, and Otherlode and the testkit use them to widen a virtual call to every override they know and to walk up to an inherited method.

## Which code shapes make an edge

Each of these records an edge from the method that contains it.

| Code shape | Edge |
|---|---|
| A method call | An edge to the callee. |
| `new Foo()` | An edge to `Foo`'s constructor. |
| Reading or writing a static field of another class in scope | An edge to that class's static initialiser. |
| A call into another class in scope | Also an edge to that class's static initialiser. |
| A lambda or method reference compiled to `invokedynamic` | A creation edge to the lambda body or the referenced method. |
| `new` or a read of `INSTANCE` on a body class | A creation edge to each method the body class declares. |

A creation edge reaches its target the way a call does. A static field access on the class's own fields records no edge, and an instance field access records none at all. With the static field rule, a class used only through its constants joins the cluster of the method that reads them.

### Body classes

A body class carries code its creator hands to someone else to run: an anonymous class, a local class, a Kotlin object expression, or a Kotlin lambda compiled to a class. Every suspend lambda is one. The agent treats the creating method as the caller of every method the body class declares, so a Ktor `handle { }` body is reached from the method that builds the route.

### Pass-throughs

Compilers generate methods that only forward a call, such as a bridge method, an `access$` accessor, Kotlin's `$default` method and the class kotlinc generates for a function reference. A forwarder has no probe of its own. The agent follows it and gives its callees to whoever referenced it, across classes if need be. So `x.f(1)` on a function with a default argument reaches `f`, and `::twice` gives its creator an edge to `twice`. If the agent cannot read a class's bytes, the edge stays as written and names the forwarder.

## Which edges are missing

The graph is static, so these leave no edge:

- **Calls through reflection, or by name from a framework.** Nothing in your bytecode names the callee.
- **A framework calling a named class of yours through an interface.** No in-scope method refers to it, so if it never ran it is a root with no caller. [Outside callers](#outside-callers) label the common cases. Lambdas and body classes are not affected, since their creator is in scope.
- **`invokedynamic` call sites other than `LambdaMetafactory`.** String concatenation and similar call sites record nothing.

## How a cluster forms

A never-hit method is a root when it has no in-scope caller at all, or when at least one of its callers has hits. The cluster grows from a root by adding each never-hit method that the cluster reaches and whose every in-scope caller is already inside the cluster.

- **A method called from two clusters belongs to neither.** Deleting either root alone would leave it called.
- **A cycle of never-hit methods that nothing outside the cycle calls has no root.** No cluster lists it. The methods still appear as never hit.
- **Whole classes fold together.** A class that never loaded, never initialised or never instantiated is a class finding, and a cluster that holds all of its methods lists the class once. A constructor of a class nothing constructed, such as a utility class's private constructor, is reached through but never listed.

Edges into Kotlin inline functions resolve to nothing, because the compiler copies an inline body into its callers. Generated methods are not nodes, but a lookup that lands on one continues along its own edges. A generated method with hits counts as a hit caller, as when a `HashMap` calls a data class's `hashCode`.

A never-loaded class joins a cluster only through the static baseline's edges, so a cluster that includes classes that never loaded needs [`staticBaselineEnabled`](configuration) on.

## Root kinds

The testkit's `RootKind` has five kinds of root.

| Root kind | What the root is | What to make of it |
|---|---|---|
| `UNCALLED` | A method with no in-scope caller and no outside caller. | The strongest case for deletion. Check [Before you delete](#before-you-delete) first. |
| `CALLED_FROM_OUTSIDE_SCOPE` | A method with no in-scope caller, but with an [outside caller](#outside-callers). | Code you do not own may call it. |
| `REACHED_FROM_HIT` | A method that a caller with hits refers to, with the call not behind an untaken branch outcome. | Not dead by structure. Read the callers the cluster names. Usually this is an override that a virtual call never reached, or a call that an exception cut short. |
| `UNTAKEN_OUTCOME` | A branch outcome that never ran, in a method that did. | Delete that side of the branch. See below. |
| `CLASS_FINDING` | A class that is never loaded, never initialised or never instantiated. | The class is the finding. Its cluster is listed only when it holds more than the methods the finding already covers. |

Otherlode has one more kind, for a method called only by tests. See its [root kinds](/docs/server/unreached-clusters#root-kinds).

### An untaken outcome roots the cluster behind it

For each edge, the agent records the innermost branch outcome its call site sits behind, and the edge counts as a call from that outcome, not from the method. Suppose an `if` in a method that runs on every request has a side that never ran, and that side constructs a `LegacyDiscountCalculator` and calls its `apply`. The untaken outcome is the root, and the constructor and `apply` join its cluster. Deleting that side of the `if` removes the cluster. An outcome root with no method behind it gives no cluster.

Routine branch outcomes, such as the null side of a null check that calls nothing, and outcomes the agent could not read, are not nodes. A call they guard starts at its method instead. See [methods and branches](methods-and-branches) for which outcomes those are.

A method called under two different untaken outcomes belongs to neither cluster.

### Outside callers

Some code is called by something outside your bytecode: a web framework calls your handler, a scheduler calls your `@Scheduled` method, and whoever holds a `Runnable` calls its `run`. The agent labels each such method with the reason code outside your scope may call it. `ProbeRef.outsideCaller` carries the label in the testkit. The reason is one of two:

- `OVERRIDES_METHOD`: the method overrides or implements a method that a type outside your scope declares, such as `java.lang.Runnable` or `java.lang.Object`. The label names that type.
- `CALLBACK_ANNOTATION`: the method, or one of its parameters, carries an annotation that a framework calls methods by, such as `@EventListener` or `@GetMapping`, or a method it overrides carries one that its framework honours there. The label names the annotation as written, so an annotation you composed yourself is named, not the one it carries.

The agent has a built-in list of callback annotations, covering Spring, Jakarta and `javax`, JAX-RS, the Kafka, Rabbit and JMS listeners, Micronaut, Quarkus, Temporal, Axon and others. For a framework the list does not cover, such as an in-house dispatcher, name its annotations in the [`callbackAnnotations`](configuration#callbackannotations) option. A named annotation counts on a method only, never on a parameter, and passes down from any superclass or interface method. An annotation that is neither on the list nor named, such as `@Nullable`, never counts.

A method takes a callback annotation from a method it overrides, in your code or outside it, where its framework does the same:

- Most web, messaging and event annotations pass down from a superclass or an interface. A Spring controller that implements a generated `XxxApi` interface carrying `@GetMapping` is labelled `@GetMapping`.
- `@Bean` passes down from a superclass method or an interface default method, not from an abstract interface method.
- Scheduling, JMS listener, actuator operation, startup, lifecycle, injection, interceptor, JPA and WebSocket callbacks never pass down.
- A method that carries any `jakarta.ws.rs` or `javax.ws.rs` annotation, on itself or a parameter, takes no JAX-RS annotation from a method it overrides.

The label names the annotation as written on the method it came from.

Temporal and Axon follow their own rules:

- A Temporal workflow annotation (`@WorkflowMethod`, `@SignalMethod`, `@QueryMethod`, `@UpdateMethod`, `@UpdateValidatorMethod`) counts from an interface method only. The method that implements it is labelled, and so is an interface's own default method that carries one. A class method that carries one itself is not.
- An interface annotated `@ActivityInterface` labels every method that implements a method of it or of its unannotated parent interfaces, and its own default methods, `@ActivityInterface`. A default method of an unannotated parent interface that nothing overrides is not labelled, so if it never ran it reads `UNCALLED`.
- `@TemporalOperation` counts on the method that carries it and never passes down.
- A composed annotation that carries a Temporal annotation counts for nothing.
- `@CommandHandler`, `@EventHandler`, `@QueryHandler` and any annotation you compose from them count, and pass down like the other event annotations. Axon's `@StartHandler`, `@ShutdownHandler`, `@EntityCreator` and `@EventCriteriaBuilder` count on the method that carries them and never pass down.

A method has at most one outside caller. Its own annotation wins, then one it inherits, then an override of an out-of-scope type.

The label changes only the root kind. The method stays never hit, and it still joins the cluster of any never-hit caller, so a never-hit `toString` reached only from dead code stays in that cluster. The static baseline's declared methods carry no outside caller.

Treat `CALLED_FROM_OUTSIDE_SCOPE` as a prompt to look. An override of `Object.toString` or `Runnable.run` that never ran in a long window is often removable. For an `@EventListener` that never fired, check whether anything still publishes the event. A never-called web handler also appears as a never-called [endpoint](endpoints).

## Before you delete

A method that never ran can still change the program by being there. Check these cases first.

- **Java serialization.** A `Serializable` class with no explicit `serialVersionUID` gets one computed from its non-private members. Deleting a non-private method changes it, and data serialized before the deletion then fails to read with `InvalidClassException`.
- **Frameworks that read metadata.** A framework can act on a method it never calls. While a lazy Spring `@Bean` method that nothing requests exists, a `@ConditionalOnMissingBean` bean of the same type is not created. Deleting the method creates that bean.
- **Reflection over members.** Code that lists `getDeclaredMethods()` or `getDeclaredFields()` and acts on the list sees one member fewer.

## Reading a cluster in the testkit

[`unreachedClusters()`](testkit-api) applies the same rules within one test JVM. It returns each cluster with its `root`, its `rootKind`, the methods it holds, and, for a `REACHED_FROM_HIT` or `CLASS_FINDING` root, the callers with hits in `reachedFrom`. The [testkit](testkit) guide covers setting it up.

```kotlin
val clusters = collector.unreachedClusters()
clusters
	.filter { it.rootKind == RootKind.UNCALLED }
	.forEach { println("${it.root.className}#${it.root.methodName}: ${it.membersTotal} methods") }
```

A cluster in a test JVM means these tests never reach it. For production, use Otherlode's view across your instances.
