---
title: Unreached code and the call graph
description: Why a list of never-hit methods is not enough, how call edges group unreached code into clusters, and what each kind of cluster root tells you about deleting it.
order: 70
---

## Why a never-hit list is not enough

A method with zero hits is a weak finding on its own. It may have zero hits only because the one method that calls it has zero hits too. If you delete the callee and leave the caller, you have gained nothing, and if you delete the caller and leave the callee, you have left dead code behind. A flat list of never-hit methods cannot tell you which method to delete first.

The agent therefore reads one more fact about each method: which other methods in your code it refers to. The server joins those references into a call graph, then groups never-hit methods into unreached clusters. An unreached cluster is a root plus every never-hit method that can be reached from the root and whose in-scope callers are all inside the cluster. Deleting the root removes the whole cluster, and the cluster tells you what you are deleting.

The rest of this page explains how the graph is built and how to read a cluster's root.

## What a call edge is

A call edge is one method's reference to one other method, or to a class's static initialiser. The agent reads it from the caller's bytecode when the class loads, and sends it once with the class. Nothing is counted per call, so edges add no cost to a request. Edges are also not observations: an edge says that the caller's code can reach the callee, not that it ever did. The hit counts say what ran.

Two limits follow from reading bytecode.

- **Only your code is in the graph.** An edge is recorded only when the callee is inside your [`includePackages`](configuration) scope. A call into the JDK, a framework or a library leaves no edge. The agent records those classes separately, as dependency usage, and never feeds them into clusters.
- **The callee is named as the bytecode names it.** The agent does not work out which override a virtual call lands on, because while it transforms one class it has no view of the rest of your hierarchy. It sends each class's superclass and interfaces with the class. The server uses them to widen a virtual call to every override it knows, and to walk up to an inherited method.

## Which code shapes make an edge

Each of these records an edge from the method that contains it.

| Code shape | Edge |
|---|---|
| A method call | An edge to the callee. |
| `new Foo()` | An edge to `Foo`'s constructor. |
| Reading or writing a static field of another class in scope | An edge to that class's static initialiser, because the access is what runs it. |
| A call into another class in scope | Also counts as a use of that class's static initialiser, which the JVM runs on first active use. |
| A lambda or method reference compiled to `invokedynamic` | An edge to the lambda body or the referenced method. It is marked as a creation edge. |
| `new` or a read of `INSTANCE` on a body class | A creation edge to each method the body class declares. |

A creation edge reaches its target the way a call does, since a lambda body can run only after the method that makes it has run. The server displays it differently, so a lambda body shows as "lambda in `handleCheckout`" instead of as its compiler name.

A static field read or write records no edge when it is on the class's own fields, and an instance field access records none at all, because the constructor edge already exists.

Static initialisers matter for a quiet reason. No bytecode ever calls `<clinit>`. Without the field-use rule, every class used only through its constants would look like an uncalled root. With it, the class joins the cluster of whatever method reads those constants.

### Body classes

A body class is a class that exists only to carry code its creator hands to someone else to run: an anonymous class, a local class, a Kotlin object expression, or a Kotlin lambda compiled to a class. Every suspend lambda is one. The agent treats the creating method as the caller of every method the body class declares. Without this rule every Ktor `handle { }` body would be an uncalled root, since only the framework calls it.

### Pass-throughs

Compilers generate methods that only forward a call. Examples are a bridge method, an `access$` accessor, Kotlin's `$default` method and the class kotlinc generates for a function reference. A forwarder has no probe of its own, so an edge into one would name a method with no hit count. Instead the agent follows the forwarder and attributes its callees to whoever referenced it, across classes if need be. So `x.f(1)` on a function with a default argument reaches `f`, and `::twice` gives its creator an edge to `twice`. If the agent cannot read a class's bytes, the edge stays as written and the server treats the callee as unknown.

## Which edges are missing

The graph is static, so it has the gaps static analysis has.

- **Calls through reflection, or by name from a framework.** Nothing in your bytecode names the callee.
- **A framework calling a named class through an interface.** If a class in your code implements a framework interface, only the framework calls it. No in-scope method refers to it, so if it never ran, it shows as a root with no caller. Lambdas and body classes are not affected, because their creator is in scope. Outside callers, described below, label the common cases.
- **Invocations other than `LambdaMetafactory`.** An `invokedynamic` records an edge only when it creates a lambda or method reference. String concatenation and similar call sites record nothing.

## How a cluster forms

Start from the never-hit methods. A method is a root when it has no in-scope caller at all, or when at least one of its callers has hits. The cluster grows from a root by adding each never-hit method that the cluster reaches and whose every in-scope caller is already inside the cluster.

Three consequences are worth knowing.

- **A method called from two clusters belongs to neither.** Deleting either root alone would leave it still called.
- **A cycle of never-hit methods with no outside caller has no root.** No cluster lists it. The methods still appear in the never-hit list.
- **Whole classes fold together.** A class that never loaded, never initialised or never instantiated is reported as a class finding, and a cluster that holds all of a class's methods lists the class once. A constructor of a class nothing constructed, such as a utility class's private constructor, is reached through but never listed.

Edges into inline functions resolve to nothing, because Kotlin copies an inline body into its callers. Generated methods are not nodes, but a lookup that lands on one continues along its own edges. A generated method with hits also counts as a hit caller, since code outside your scope calls it. A `HashMap` calling a data class's `hashCode` is the usual case.

A never-loaded class joins a cluster only through the static baseline's edges, so a cluster that includes classes that never loaded needs [`staticBaselineEnabled`](configuration) on.

## Root kinds

The testkit's `RootKind` names five kinds of root. They call for different actions, so they are reported apart.

| Root kind | What the root is | What to make of it |
|---|---|---|
| `UNCALLED` | A method with no in-scope caller and no outside caller. | The strongest case for deletion. Nothing in your code calls it and nothing outside is known to. Check for reflection first. |
| `CALLED_FROM_OUTSIDE_SCOPE` | A method with no in-scope caller, but with an outside caller. | Weaker evidence. Code you do not own may call it. See below. |
| `REACHED_FROM_HIT` | A method that a caller with hits refers to, with the call not behind an untaken branch outcome. | Not dead by structure. Read the callers the cluster names. Mostly this is an override that a virtual call never reached, or a call that an exception cut short. |
| `UNTAKEN_OUTCOME` | A branch outcome that never ran, in a method that did. | The most actionable kind. See below. |
| `CLASS_FINDING` | A class that is never loaded, never initialised or never instantiated. | The class is the finding. Its cluster is listed only when it holds more than the methods the finding already covers. |

When the server also receives [test runs](test-runs), it adds a kind for a method called only by tests. The testkit does not have it.

### An untaken outcome roots the cluster behind it

Suppose an `if` in a method that runs on every request has a branch that has never been taken, and that branch constructs a `LegacyDiscountCalculator` and calls its `apply`. Without outcomes in the graph, the constructor and `apply` would be separate roots, each "reached from hit" because the checkout handler ran. Nothing would tell you they are one feature.

The agent records, for each edge, the innermost branch outcome that its call site sits behind. The server treats an edge as a call from that outcome, not from the method. If the outcome never ran, the outcome is the root, and everything reachable only through it joins the cluster. Deleting that side of the `if` removes the cluster. An outcome root that has no method behind it gives no cluster, since the finding already says everything there is.

Branch outcomes that are routine, like a null check's null side, or that the agent could not read, are not nodes. A call they guard starts at its method instead. See [methods and branches](methods-and-branches) for which outcomes those are.

Two caveats. A method called under two different untaken outcomes belongs to neither cluster, as in the shared-method case. And the claim is only as good as the observation window, as it is for every other finding.

### Outside callers

Some code is called by something that is not in your bytecode. A web framework calls your handler, a scheduler calls your `@Scheduled` method, and `Runnable.run` is called by whoever holds a `Runnable`. With only your code's edges, all of these look uncalled.

The agent therefore labels each method with the reason code outside your scope may call it, when there is one. The reason is one of two:

- `OVERRIDES_METHOD`: the method overrides or implements a method that a type outside your scope declares, such as `java.lang.Runnable` or `java.lang.Object`. The label names that type.
- `CALLBACK_ANNOTATION`: the method, or one of its parameters, carries an annotation that a framework calls methods by, such as `@EventListener` or `@GetMapping`. The label names the annotation as written, so an annotation you composed yourself is named, not the one it carries.

The agent has a built-in list of callback annotations, covering Spring, Jakarta and `javax`, JAX-RS, the Kafka, Rabbit and JMS listeners, Micronaut, Quarkus and others. For a framework the list does not cover, such as Axon or an in-house dispatcher, name its annotations in the [`callbackAnnotations`](configuration#callbackannotations) option. A named annotation counts on a method only, never on a parameter. An annotation that is neither on the list nor named, such as `@Nullable`, never counts, because it would hide real dead code.

A method has at most one outside caller. An annotation wins over an override, because it says more about who calls the method. `ProbeRef.outsideCaller` carries the label in the testkit.

The label changes nothing but the name of the root kind. The method stays never hit, and it still joins the cluster of any never-hit caller. A never-hit `toString` reached only from dead code stays in that cluster.

Treat `CALLED_FROM_OUTSIDE_SCOPE` as a prompt to look, not a verdict. An override of `Object.toString` or `Runnable.run` that never ran in a long window is often removable, and the label tells you why the agent could not say "uncalled". An `@EventListener` that never fired is a different question, one about whether the event still exists. A never-called web handler also appears as a never-called endpoint, so the two views agree.

The static baseline's declared methods carry no outside caller. A never-loaded class's methods fold into the class finding anyway.

## Reading a cluster in the testkit

[`unreachedClusters()`](testkit-api) applies the same rule within one test JVM, and returns each cluster with its root, its `rootKind`, the methods it holds, and the callers with hits that a `REACHED_FROM_HIT` root names in `reachedFrom`. The [testkit](testkit) guide covers setting it up.

```kotlin
val clusters = collector.unreachedClusters()
clusters
	.filter { it.rootKind == RootKind.UNCALLED }
	.forEach { println("${it.root.className}#${it.root.methodName}: ${it.membersTotal} methods") }
```

A test JVM sees only what its tests exercise, so a cluster there means "these tests never reach it". Judge production by the server's view across your instances.
